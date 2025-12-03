package org.example;

import ai.djl.huggingface.tokenizers.Encoding;
import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.onnxruntime.*;

import java.io.File;
import java.nio.file.Path;
import java.util.*;

/**
 * Cross-encoder scorer:
 * - if ONNX model available in CROSS_ENCODER_ONNX_DIR, uses it with HuggingFace tokenizer.
 * - otherwise falls back to scoring with bi-encoder similarity (dot/cosine) using provided OnnxEmbedder.
 */
public class CrossEncoderScorer implements AutoCloseable {
    private final OnnxEmbedder embedder; // used for fallback
    private OrtEnvironment env;
    private OrtSession session;
    private HuggingFaceTokenizer tokenizer;
    private boolean useOnnx = false;
    private final int maxSeqLen = 512;

    public CrossEncoderScorer(OnnxEmbedder embedder) {
        this.embedder = embedder;
        try {
            File onnxModel = new File(Config.CROSS_ENCODER_ONNX_DIR + File.separator + "model.onnx");
            File tokenizerDir = new File(Config.CROSS_ENCODER_ONNX_DIR);
            if (onnxModel.exists()) {
                env = OrtEnvironment.getEnvironment();
                OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
                session = env.createSession(onnxModel.getAbsolutePath(), opts);
                tokenizer = HuggingFaceTokenizer.newInstance(Path.of(tokenizerDir.getAbsolutePath()));
                useOnnx = true;
                System.out.println("CrossEncoderScorer: loaded ONNX model from " + onnxModel.getAbsolutePath());
            } else {
                useOnnx = false;
                System.out.println("CrossEncoderScorer: ONNX model not found, using bi-encoder fallback");
            }
        } catch (Exception e) {
            System.err.println("CrossEncoderScorer: failed to load ONNX, using fallback: " + e.getMessage());
            useOnnx = false;
        }
    }

    // Batch scoring fallback: use embedder to embed query and doc and compute cosine
    private static double cosine(float[] a, float[] b) {
        if (a==null || b==null || a.length!=b.length) return 0.0;
        double dot=0, na=0, nb=0;
        for (int i=0;i<a.length;i++){ dot += a[i]*b[i]; na += a[i]*a[i]; nb += b[i]*b[i]; }
        if (na==0 || nb==0) return 0.0;
        return dot / (Math.sqrt(na)*Math.sqrt(nb));
    }

    /**
     * Score a batch of doc texts for a single query.
     * Returns map: chunkId -> score (higher is better).
     */
    public Map<String, Float> scoreBatch(String query, List<DbChunk> docs) throws Exception {
        Map<String, Float> out = new HashMap<>();
        if (docs == null || docs.isEmpty()) return out;

        if (!useOnnx) {
            // Fallback: compute embedding for query and each doc via embedder
            float[] qv = embedder.embed(query);
            for (DbChunk d : docs) {
                float[] dv = embedder.embed(d.getText());
                double s = cosine(qv, dv);
                out.put(d.getChunkId(), (float) s);
            }
            return out;
        }

        // ONNX cross-encoder scoring
        return scoreBatchOnnx(query, docs);
    }

    /**
     * ONNX batched cross-encoder scoring.
     * Tokenizes (query, doc) pairs and runs through the cross-encoder model.
     */
    private Map<String, Float> scoreBatchOnnx(String query, List<DbChunk> docs) throws Exception {
        Map<String, Float> out = new HashMap<>();
        int batchSize = docs.size();

        // Tokenize each (query, doc) pair
        List<long[]> idsList = new ArrayList<>(batchSize);
        List<long[]> maskList = new ArrayList<>(batchSize);
        List<long[]> typeIdsList = new ArrayList<>(batchSize);
        int maxLen = 0;

        for (DbChunk doc : docs) {
            String text = doc.getText() != null ? doc.getText() : "";
            // Encode pair: [CLS] query [SEP] doc [SEP]
            Encoding enc = tokenizer.encode(query, text);
            long[] ids = enc.getIds();
            long[] mask = enc.getAttentionMask();
            long[] typeIds = enc.getTypeIds();

            // Truncate if needed
            if (ids.length > maxSeqLen) {
                ids = Arrays.copyOf(ids, maxSeqLen);
                mask = Arrays.copyOf(mask, maxSeqLen);
                typeIds = Arrays.copyOf(typeIds, maxSeqLen);
            }
            idsList.add(ids);
            maskList.add(mask);
            typeIdsList.add(typeIds);
            maxLen = Math.max(maxLen, ids.length);
        }

        // Pad to maxLen
        long[][] inputIds = new long[batchSize][maxLen];
        long[][] attentionMask = new long[batchSize][maxLen];
        long[][] tokenTypeIds = new long[batchSize][maxLen];
        for (int i = 0; i < batchSize; i++) {
            Arrays.fill(inputIds[i], 0L);
            Arrays.fill(attentionMask[i], 0L);
            Arrays.fill(tokenTypeIds[i], 0L);
            System.arraycopy(idsList.get(i), 0, inputIds[i], 0, idsList.get(i).length);
            System.arraycopy(maskList.get(i), 0, attentionMask[i], 0, maskList.get(i).length);
            System.arraycopy(typeIdsList.get(i), 0, tokenTypeIds[i], 0, typeIdsList.get(i).length);
        }

        // Create ONNX tensors and run
        try (OnnxTensor idsTensor = OnnxTensor.createTensor(env, inputIds);
             OnnxTensor maskTensor = OnnxTensor.createTensor(env, attentionMask);
             OnnxTensor typeIdsTensor = OnnxTensor.createTensor(env, tokenTypeIds)) {

            Map<String, OnnxTensor> inputs = new HashMap<>();
            inputs.put("input_ids", idsTensor);
            inputs.put("attention_mask", maskTensor);
            inputs.put("token_type_ids", typeIdsTensor);

            try (OrtSession.Result result = session.run(inputs)) {
                // Get logits output - shape [batch, num_labels] or [batch, 1]
                OnnxValue output = result.get(0);
                if (output instanceof OnnxTensor tensor) {
                    Object val = tensor.getValue();
                    float[][] logits;
                    if (val instanceof float[][] f2d) {
                        logits = f2d;
                    } else if (val instanceof float[] f1d) {
                        // Single output per sample
                        logits = new float[batchSize][1];
                        for (int i = 0; i < batchSize; i++) {
                            logits[i][0] = f1d[i];
                        }
                    } else {
                        throw new RuntimeException("Unexpected cross-encoder output type: " + val.getClass());
                    }

                    // Map scores
                    for (int i = 0; i < batchSize; i++) {
                        float score = logits[i][0]; // Take first logit as relevance score
                        out.put(docs.get(i).getChunkId(), score);
                    }
                }
            }
        }
        return out;
    }

    @Override
    public void close() throws Exception {
        if (session != null) session.close();
        if (env != null) env.close();
        if (tokenizer != null) tokenizer.close();
    }
}
