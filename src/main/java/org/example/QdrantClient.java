package org.example;

import okhttp3.*;
import com.fasterxml.jackson.databind.*;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.*;

public class QdrantClient {
    private final OkHttpClient http = new OkHttpClient();
    private final ObjectMapper M = new ObjectMapper();
    private final String baseUrl;
    private final String collection;

    public QdrantClient(String baseUrl, String collection) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length()-1) : baseUrl;
        this.collection = collection;
    }

    public List<Candidate> search(float[] vector, int topK, int ef) throws IOException {
        String url = baseUrl + "/collections/" + collection + "/points/search";
        ObjectNode body = M.createObjectNode();
        body.set("vector", M.valueToTree(vector));
        body.put("limit", topK);
        body.put("with_payload", true);
        body.put("with_vector", true);
        ObjectNode params = M.createObjectNode();
        params.put("ef", ef);
        body.set("params", params);

        Request req = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                .build();

        try (Response res = http.newCall(req).execute()) {
            if (!res.isSuccessful()) throw new IOException("Qdrant search failed: " + res.code() + " " + res.message());
            JsonNode root = M.readTree(res.body().string());
            JsonNode result = root.get("result");
            List<Candidate> out = new ArrayList<>();
            if (result != null && result.isArray()) {
                for (JsonNode r : result) {
                    Candidate c = new Candidate();
                    c.id = r.get("id").asText();
                    c.score = r.has("score") ? r.get("score").asDouble() : 0.0;
                    c.payload = r.has("payload") ? M.convertValue(r.get("payload"), Map.class) : null;
                    if (r.has("vector") && r.get("vector").isArray()) {
                        JsonNode v = r.get("vector");
                        float[] fv = new float[v.size()];
                        for (int i = 0; i < v.size(); i++) fv[i] = (float) v.get(i).asDouble();
                        c.vector = fv;
                    } else {
                        c.vector = null;
                    }
                    out.add(c);
                }
            }
            return out;
        }
    }

    public Map<String, Candidate> getPoints(List<String> ids) throws IOException {
        if (ids == null || ids.isEmpty()) return Collections.emptyMap();
        String url = baseUrl + "/collections/" + collection + "/points";
        ObjectNode body = M.createObjectNode();
        body.set("ids", M.valueToTree(ids));
        body.put("with_payload", true);
        body.put("with_vector", true);

        Request req = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                .build();

        try (Response res = http.newCall(req).execute()) {
            if (!res.isSuccessful()) throw new IOException("Qdrant getPoints failed: " + res.code() + " " + res.message());
            JsonNode root = M.readTree(res.body().string());
            JsonNode result = root.get("result");
            Map<String, Candidate> map = new HashMap<>();
            if (result != null && result.has("points")) {
                for (JsonNode p : result.get("points")) {
                    Candidate c = new Candidate();
                    c.id = p.get("id").asText();
                    c.payload = p.has("payload") ? M.convertValue(p.get("payload"), Map.class) : null;
                    if (p.has("vector") && p.get("vector").isArray()) {
                        JsonNode v = p.get("vector");
                        float[] fv = new float[v.size()];
                        for (int i = 0; i < v.size(); i++) fv[i] = (float) v.get(i).asDouble();
                        c.vector = fv;
                    }
                    map.put(c.id, c);
                }
            }
            return map;
        }
    }

    /**
     * Get points by chunk_id (payload field), not by Qdrant UUID.
     * Uses scroll with filter to find points matching the given chunk_ids.
     */
    public Map<String, Candidate> getPointsByChunkIds(List<String> chunkIds) throws IOException {
        if (chunkIds == null || chunkIds.isEmpty()) return Collections.emptyMap();
        
        // Use scroll API with filter on payload.chunk_id
        String url = baseUrl + "/collections/" + collection + "/points/scroll";
        ObjectNode body = M.createObjectNode();
        body.put("limit", chunkIds.size());
        body.put("with_payload", true);
        body.put("with_vector", true);
        
        // Build filter: { "should": [ {"key": "chunk_id", "match": {"value": "X"}}, ... ] }
        ObjectNode filter = M.createObjectNode();
        var shouldArray = M.createArrayNode();
        for (String cid : chunkIds) {
            ObjectNode condition = M.createObjectNode();
            condition.put("key", "chunk_id");
            ObjectNode match = M.createObjectNode();
            match.put("value", cid);
            condition.set("match", match);
            shouldArray.add(condition);
        }
        filter.set("should", shouldArray);
        body.set("filter", filter);

        Request req = new Request.Builder()
                .url(url)
                .post(RequestBody.create(body.toString(), MediaType.parse("application/json")))
                .build();

        try (Response res = http.newCall(req).execute()) {
            if (!res.isSuccessful()) throw new IOException("Qdrant scroll failed: " + res.code() + " " + res.message());
            JsonNode root = M.readTree(res.body().string());
            JsonNode result = root.get("result");
            Map<String, Candidate> map = new HashMap<>();
            if (result != null && result.has("points")) {
                for (JsonNode p : result.get("points")) {
                    Candidate c = new Candidate();
                    c.id = p.get("id").asText();
                    c.payload = p.has("payload") ? M.convertValue(p.get("payload"), Map.class) : null;
                    if (p.has("vector") && p.get("vector").isArray()) {
                        JsonNode v = p.get("vector");
                        float[] fv = new float[v.size()];
                        for (int i = 0; i < v.size(); i++) fv[i] = (float) v.get(i).asDouble();
                        c.vector = fv;
                    }
                    // Key by chunk_id from payload, not by Qdrant UUID
                    String chunkId = c.payload != null && c.payload.containsKey("chunk_id") 
                            ? c.payload.get("chunk_id").toString() 
                            : c.id;
                    map.put(chunkId, c);
                }
            }
            return map;
        }
    }
}

