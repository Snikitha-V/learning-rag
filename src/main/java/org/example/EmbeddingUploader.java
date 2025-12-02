package org.example;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import okhttp3.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * EmbeddingUploader
 *
 * - Reads chunks.jsonl
 * - Embeds chunk 'text' using DJL + HuggingFace sentence-transformer
 * - Normalizes embeddings and upserts to Qdrant via HTTP
 *
 * Usage:
 *   set QDRANT_URL=http://localhost:6333
 *   mvn -Dexec.mainClass=org.example.EmbeddingUploader -Dexec.args="chunks.jsonl" exec:java
 *
 * Notes:
 * - Requires DJL native libs for PyTorch if using GPU. If not available, DJL will fall back to CPU.
 * - The program uses OkHttp to call Qdrant REST API /points/upsert.
 */
public class EmbeddingUploader {

    private static final ObjectMapper M = new ObjectMapper();
    private static final String QDRANT_ENV = "QDRANT_URL";
    private static final String DEFAULT_QDRANT = "http://localhost:6333";
    private static final String COLLECTION_NAME = "learning_chunks";
    private static final int BATCH_SIZE = 8; // tune for GPU memory; lower if OOM
    private static final int EMBEDDING_DIM = 768; // all-mpnet-base-v2 => 768 dims

    public static void main(String[] args) throws Exception {
        String file = args.length > 0 ? args[0] : "chunks.jsonl";
        String qdrantUrl = System.getenv().getOrDefault(QDRANT_ENV, DEFAULT_QDRANT);

        System.out.println("Reading chunks from: " + file);
        List<JsonNode> chunks = readJsonl(file);
        System.out.println("Loaded chunks: " + chunks.size());

        // ensure Qdrant collection exists
        ensureQdrantCollection(qdrantUrl, COLLECTION_NAME, EMBEDDING_DIM);

        // initialize ONNX embedder
        System.out.println("Loading embedding model via ONNX Runtime...");
        try (OnnxEmbedder embedder = new OnnxEmbedder(
                "models/all-mpnet-base-v2-onnx/model.onnx",
                "models/all-mpnet-base-v2-onnx", // pass directory (tokenizer.json inside)
                384
        )) {
            embedAndUploadBatches(chunks, embedder, qdrantUrl);
        }

        System.out.println("Done. All vectors upserted to Qdrant collection: " + COLLECTION_NAME);
    }

    // ---- read chunks.jsonl ----
    private static List<JsonNode> readJsonl(String path) throws IOException {
        List<JsonNode> out = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(new FileInputStream(path), StandardCharsets.UTF_8))) {
            String line;
            while ((line = r.readLine()) != null) {
                if (line.trim().isEmpty()) continue;
                out.add(M.readTree(line));
            }
        }
        return out;
    }

    // ---- main batching loop; compute embeddings and upload to Qdrant ----
    private static void embedAndUploadBatches(List<JsonNode> chunks,
                                              OnnxEmbedder embedder,
                                              String qdrantUrl) throws Exception {
        OkHttpClient http = new OkHttpClient.Builder()
                .callTimeout(2, TimeUnit.MINUTES)
                .connectTimeout(30, TimeUnit.SECONDS)
                .build();

        List<String> texts = new ArrayList<>(BATCH_SIZE);
        List<JsonNode> batchChunks = new ArrayList<>(BATCH_SIZE);

        for (int i = 0; i < chunks.size(); i++) {
            JsonNode chunk = chunks.get(i);
            String text = extractTextForEmbedding(chunk);
            texts.add(text);
            batchChunks.add(chunk);

            if (texts.size() >= BATCH_SIZE || i == chunks.size() - 1) {
                // compute embeddings using ONNX
                float[][] embeddings = embedder.embed(texts);
                // normalize and prepare Qdrant points
                List<ObjectNode> points = prepareQdrantPoints(batchChunks, embeddings);
                // upsert to Qdrant
                upsertToQdrant(http, qdrantUrl, COLLECTION_NAME, points);
                // clear batch
                texts.clear();
                batchChunks.clear();
            }
        }
    }

    // Choose which text to embed (we use 'text' field; fallback to 'title')
    private static String extractTextForEmbedding(JsonNode chunk) {
        JsonNode text = chunk.get("text");
        if (text != null && !text.asText().trim().isEmpty()) return text.asText();
        JsonNode title = chunk.get("title");
        return title == null ? "" : title.asText();
    }

    // Prepare Qdrant 'points' array with normalized vectors and metadata
    // Uses deterministic UUIDv3-like ids derived from chunk_id so upserts are idempotent.
    private static List<ObjectNode> prepareQdrantPoints(List<JsonNode> chunks, float[][] embeddings) {
        List<ObjectNode> points = new ArrayList<>(chunks.size());
        for (int i = 0; i < chunks.size(); i++) {
            JsonNode chunk = chunks.get(i);

            // get stable chunk id string (fallback to generated if missing)
            String chunkIdStr = chunk.has("chunk_id") ? chunk.get("chunk_id").asText() : "auto-" + UUID.randomUUID();

            // create deterministic UUID from chunkIdStr so same chunk => same UUID
            UUID uuid = UUID.nameUUIDFromBytes(chunkIdStr.getBytes(java.nio.charset.StandardCharsets.UTF_8));
            String uuidStr = uuid.toString(); // this is valid for Qdrant

            float[] vec = embeddings[i];
            l2Normalize(vec);

            ObjectNode point = M.createObjectNode();
            // Qdrant requires unsigned int or UUID; we use UUID string
            point.put("id", uuidStr);

            // vector
            point.set("vector", M.valueToTree(vec));

            // payload = store original chunk_id and selected metadata for search/filters
            ObjectNode payload = M.createObjectNode();
            payload.put("chunk_id", chunkIdStr); // keep original human-friendly id in payload
            if (chunk.has("title")) payload.put("title", chunk.get("title").asText());
            if (chunk.has("chunk_type")) payload.put("chunk_type", chunk.get("chunk_type").asText());
            if (chunk.has("metadata")) payload.set("metadata", chunk.get("metadata"));

            point.set("payload", payload);
            points.add(point);
        }
        return points;
    }

    // Upsert using Qdrant HTTP API
    private static void upsertToQdrant(OkHttpClient http, String qdrantUrl, String collection, List<ObjectNode> points) throws IOException {
        // PUT to /collections/{collection}/points with upsert semantics
        String url = qdrantUrl + "/collections/" + collection + "/points?wait=true";

        // Build request body: {"points": [ { "id": ..., "vector": [...], "payload": {...} }, ... ]}
        ObjectNode body = M.createObjectNode();
        body.set("points", M.valueToTree(points));

        RequestBody rb = RequestBody.create(body.toString(), MediaType.parse("application/json"));
        Request req = new Request.Builder()
                .url(url)
                .put(rb)  // Use PUT for upsert
                .addHeader("Accept", "application/json")
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                String respBody = resp.body() != null ? resp.body().string() : "<no-body>";
                throw new IOException("Qdrant upsert failed: " + resp.code() + " " + resp.message() + " : " + respBody);
            } else {
                System.out.println("Upserted " + points.size() + " points to Qdrant.");
            }
        }
    }

    // ensure collection exists in Qdrant, create if missing
    private static void ensureQdrantCollection(String qdrantUrl, String name, int dim) throws IOException {
        OkHttpClient http = new OkHttpClient();
        String checkUrl = qdrantUrl + "/collections/" + name;
        Request check = new Request.Builder().url(checkUrl).get().build();
        try (Response r = http.newCall(check).execute()) {
            if (r.isSuccessful()) {
                System.out.println("Qdrant collection exists: " + name);
                return;
            }
        }

        // create collection with correct Qdrant API format
        String createUrl = qdrantUrl + "/collections/" + name;
        
        // vectors config: { "size": dim, "distance": "Cosine" }
        ObjectNode vectorsConfig = M.createObjectNode();
        vectorsConfig.put("size", dim);
        vectorsConfig.put("distance", "Cosine");

        ObjectNode body = M.createObjectNode();
        body.set("vectors", vectorsConfig);

        RequestBody rb = RequestBody.create(body.toString(), MediaType.parse("application/json"));
        Request req = new Request.Builder().url(createUrl).put(rb).build();
        try (Response resp = http.newCall(req).execute()) {
            if (!resp.isSuccessful()) {
                ResponseBody respBody = resp.body();
                String b = respBody != null ? respBody.string() : "<no-body>";
                throw new IOException("Failed to create Qdrant collection: " + resp.code() + " : " + b);
            } else {
                System.out.println("Created Qdrant collection: " + name);
            }
        }
    }

    // L2-normalize vector in place
    private static void l2Normalize(float[] vec) {
        double sum = 0.0;
        for (float v : vec) sum += (double) v * v;
        double norm = Math.sqrt(sum);
        if (norm == 0.0) return;
        for (int i = 0; i < vec.length; i++) vec[i] = (float) (vec[i] / norm);
    }
}
