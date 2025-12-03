package org.example;

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer;
import ai.djl.huggingface.tokenizers.Encoding;
import ai.onnxruntime.*;

import java.nio.file.Path;
import java.util.*;

/**
 * OnnxEmbedder - loads a local ONNX sentence-transformer and a local tokenizer,
 * produces L2-normalized embeddings for a batch of strings.
 *
 * Usage:
 *   OnnxEmbedder embedder = new OnnxEmbedder("models/all-mpnet-base-v2-onnx/model.onnx",
 *                                            "models/all-mpnet-base-v2-onnx", 384);
 *   float[][] embs = embedder.embed(List.of("hello", "world"));
 *   embedder.close();
 */
public class OnnxEmbedder implements AutoCloseable {

    private final OrtEnvironment env;
    private final OrtSession session;
    private final HuggingFaceTokenizer tokenizer;
    private final int maxLen;

    public OnnxEmbedder(String modelPath, String tokenizerFolderOrFile, int maxLen) throws Exception {
        this.env = OrtEnvironment.getEnvironment();
        OrtSession.SessionOptions opts = new OrtSession.SessionOptions();
        // If you later want GPU provider, add provider here.
        this.session = env.createSession(modelPath, opts);

        // Create tokenizer from local path (folder or tokenizer.json)
        this.tokenizer = HuggingFaceTokenizer.newInstance(Path.of(tokenizerFolderOrFile));
        this.maxLen = maxLen;

        // debug: print input and output names
        System.out.println("ONNX session input names: " + session.getInputNames());
        System.out.println("ONNX session output names: " + session.getOutputNames());
    }

    /**
     * Embed a single text. Returns float[dim] (L2-normalized).
     * Convenience wrapper around embed(List).
     */
    public float[] embed(String text) throws Exception {
        float[][] result = embed(List.of(text));
        return result.length > 0 ? result[0] : new float[0];
    }

    /**
     * Embed a list of texts. Returns float[batch][dim] (L2-normalized).
     */
    public float[][] embed(List<String> texts) throws Exception {
        if (texts == null || texts.isEmpty()) return new float[0][0];

        int bs = texts.size();

        // Tokenize and collect truncated ids + attention masks
        List<long[]> idsList = new ArrayList<>(bs);
        List<long[]> maskList = new ArrayList<>(bs);
        int seqLen = 0;

        for (String t : texts) {
            Encoding enc = tokenizer.encode(t); // tokenizer adds special tokens by default
            long[] ids = enc.getIds();
            long[] mask = enc.getAttentionMask();

            // truncate if necessary
            if (ids.length > maxLen) {
                ids = Arrays.copyOf(ids, maxLen);
                mask = Arrays.copyOf(mask, maxLen);
            }
            idsList.add(ids);
            maskList.add(mask);
            seqLen = Math.max(seqLen, ids.length);
        }

        // pad to seqLen
        long[][] inputIds = new long[bs][seqLen];
        long[][] attentionMask = new long[bs][seqLen];
        for (int i = 0; i < bs; i++) {
            Arrays.fill(inputIds[i], 0L);
            Arrays.fill(attentionMask[i], 0L);
            long[] ids = idsList.get(i);
            long[] mask = maskList.get(i);
            System.arraycopy(ids, 0, inputIds[i], 0, ids.length);
            System.arraycopy(mask, 0, attentionMask[i], 0, mask.length);
        }

        // Create Onnx tensors
        try (OnnxTensor idsTensor = OnnxTensor.createTensor(env, inputIds);
             OnnxTensor maskTensor = OnnxTensor.createTensor(env, attentionMask)) {

            Map<String, OnnxTensor> inputs = new HashMap<>();
            Set<String> inputNames = session.getInputNames();

            // pick input names conservatively
            String idsName = inputNames.contains("input_ids") ? "input_ids" : inputNames.iterator().next();
            inputs.put(idsName, idsTensor);
            if (inputNames.contains("attention_mask")) {
                inputs.put("attention_mask", maskTensor);
            } else if (inputNames.contains("attentionMask")) {
                inputs.put("attentionMask", maskTensor);
            } else {
                // if attention_mask not expected, we still provided ids only
            }

            try (OrtSession.Result result = session.run(inputs)) {
                // pick first output
                OnnxValue first = null;
                @SuppressWarnings("unused")
                String firstName = null;
                for (Map.Entry<String, OnnxValue> e : result) {
                    firstName = e.getKey();
                    first = e.getValue();
                    break;
                }
                if (first == null) {
                    throw new RuntimeException("ONNX run returned no outputs");
                }

                // convert output to float[][], handling common shapes:
                // - float[batch][dim]  (pooled embedding)
                // - float[batch][seq][dim] (last_hidden_state -> mean-pool)
                // - double[][] or double[][][] similarly
                float[][] embeddings;

                if (first instanceof OnnxTensor t) {
                    Object val = t.getValue();
                    // case: float[batch][dim]
                    if (val instanceof float[][] floatArr) {
                        embeddings = floatArr;
                    }
                    // case: float[] flat (batch*dim)
                    else if (val instanceof float[] flat) {
                        int dim = flat.length / bs;
                        embeddings = new float[bs][dim];
                        for (int i = 0; i < bs; i++) {
                            System.arraycopy(flat, i * dim, embeddings[i], 0, dim);
                        }
                    }
                    // case: float[batch][seq][dim] -> mean over seq
                    else if (val instanceof float[][][] arr3) {
                        int dim = arr3[0][0].length;
                        embeddings = new float[bs][dim];
                        for (int i = 0; i < bs; i++) {
                            int seq = arr3[i].length;
                            float[] sum = new float[dim];
                            for (int s = 0; s < seq; s++) {
                                for (int d = 0; d < dim; d++) sum[d] += arr3[i][s][d];
                            }
                            for (int d = 0; d < dim; d++) embeddings[i][d] = sum[d] / Math.max(1, seq);
                        }
                    }
                    // case: double[][] -> convert
                    else if (val instanceof double[][] darr) {
                        embeddings = new float[darr.length][darr[0].length];
                        for (int i = 0; i < darr.length; i++)
                            for (int j = 0; j < darr[0].length; j++) embeddings[i][j] = (float) darr[i][j];
                    }
                    // case: double[][][] -> mean over seq
                    else if (val instanceof double[][][] darr3) {
                        int dim = darr3[0][0].length;
                        embeddings = new float[bs][dim];
                        for (int i = 0; i < bs; i++) {
                            int seq = darr3[i].length;
                            double[] sum = new double[dim];
                            for (int s = 0; s < seq; s++) {
                                for (int d = 0; d < dim; d++) sum[d] += darr3[i][s][d];
                            }
                            for (int d = 0; d < dim; d++) embeddings[i][d] = (float) (sum[d] / Math.max(1, seq));
                        }
                    } else if (val != null) {
                        // generic fallback: coerce via Object[] nesting
                        Object[] arr = (Object[]) val;
                        int n = arr.length;
                        Object firstRow = arr[0];
                        if (firstRow instanceof Object[] row0) {
                            int dim = row0.length;
                            embeddings = new float[n][dim];
                            for (int i = 0; i < n; i++) {
                                Object[] row = (Object[]) arr[i];
                                for (int j = 0; j < dim; j++) embeddings[i][j] = ((Number) row[j]).floatValue();
                            }
                        } else {
                            throw new RuntimeException("Unhandled ONNX output shape, type: " + val.getClass());
                        }
                    } else {
                        throw new RuntimeException("ONNX output value is null");
                    }
                } else {
                    throw new RuntimeException("ONNX output not a tensor: " + first.getClass());
                }

                // L2-normalize each vector
                for (float[] embedding : embeddings) {
                    l2Normalize(embedding);
                }
                return embeddings;
            }
        }
    }

    private static void l2Normalize(float[] vec) {
        double sum = 0.0;
        for (float v : vec) sum += (double) v * v;
        double norm = Math.sqrt(sum);
        if (norm == 0.0) return;
        for (int i = 0; i < vec.length; i++) vec[i] = (float) (vec[i] / norm);
    }

    @Override
    public void close() throws Exception {
        try {
            if (session != null) session.close();
        } finally {
            if (env != null) env.close();
            if (tokenizer != null) tokenizer.close();
        }
    }

    // Debug/test method to verify embedding dimensions and L2 normalization
    public static void sanityCheckEmbed(OnnxEmbedder embedder) throws Exception {
        float[] v = embedder.embed("test normalization check");
        double sumSq = 0;
        for (float f : v) sumSq += f * f;
        System.out.println("embed.length=" + v.length + " norm=" + Math.sqrt(sumSq));
    }
}
