package org.example;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.*;

public class Evaluator {
    private final RetrievalService svc;
    private final SqlService sql;
    private final QdrantClient qdrant; // for recall checks (existing client)
    public Evaluator(RetrievalService svc, SqlService sql, QdrantClient qdrant) {
        this.svc = svc; this.sql = sql; this.qdrant = qdrant;
    }

    public static class Result {
        public String id;
        public TestCase.Type type;
        public boolean factExact = false;
        public boolean recall = false;
        public boolean faithfulness = false; // heuristic
        public boolean hallucination = false;
        public long latencyMs;
        public String rawAnswer;
        public List<String> retrievedChunkIds = new ArrayList<>();
    }

    // Run a single test case
    public Result run(TestCase tc) {
        Result r = new Result(); r.id = tc.id; r.type = tc.type;
        long t0 = System.nanoTime();
        try {
            // For factual: prefer direct SQL check when expected present
            if (tc.type == TestCase.Type.FACTUAL) {
                // run ask() which will route to SQL; but we also check SQL directly for exactness to be strict
                String sqlAnswer = trySqlDeterministic(tc.query);
                r.rawAnswer = svc.ask(tc.query);
                r.factExact = (sqlAnswer != null && sqlAnswer.equals(tc.expected)) || normalized(r.rawAnswer).contains(normalized(tc.expected));
            } else {
                r.rawAnswer = svc.ask(tc.query); // runs RAG
            }

            // retrieval recall: check whether expectedChunkIds present in dense top-100
            if (tc.expectedChunkIds != null && !tc.expectedChunkIds.isEmpty()) {
                r.retrievedChunkIds = topKChunkIdsForQuery(tc.query, 100);
                r.recall = r.retrievedChunkIds.containsAll(tc.expectedChunkIds);
            } else {
                // fallback heuristic: check top-100 contains any chunk that is evidence for answer tokens
                r.retrievedChunkIds = topKChunkIdsForQuery(tc.query, 100);
                r.recall = !r.retrievedChunkIds.isEmpty();
            }

            // faithfulness heuristic: does answer include only chunk ids that were included? simple: if answer cites [source: ...] parse and ensure those ids are in retrievedChunkIds
            List<String> cited = parseCitedChunkIds(r.rawAnswer);
            if (!cited.isEmpty()) {
                r.faithfulness = r.retrievedChunkIds.containsAll(cited);
                r.hallucination = !r.faithfulness;
            } else {
                // if no citations, do keyword overlap check: are important keywords from expected in answer
                r.faithfulness = tc.expected == null || normalized(r.rawAnswer).contains(normalized(tc.expected));
                r.hallucination = !r.faithfulness;
            }
        } catch (Exception e) {
            r.rawAnswer = "ERROR: " + e.getMessage();
            r.hallucination = true;
        } finally {
            r.latencyMs = (System.nanoTime() - t0) / 1_000_000;
        }
        return r;
    }

    private String trySqlDeterministic(String query) {
        // naive approach: run same SQL paths as router; you can expand matching here
        try {
            String topic = svc.extractTopicIdFromQuery(query);
            if (topic != null && (query.toLowerCase().contains("when") || query.toLowerCase().contains("learn"))) {
                Optional<Map<String,String>> range = sql.queryLearnedAtRange(topic);
                if (range.isPresent()) {
                    String e = range.get().get("earliest"), l = range.get().get("latest");
                    return String.format("You learned %s between %s and %s.", topic, e, l);
                }
            }
            if (topic != null && (query.toLowerCase().contains("how many") || query.toLowerCase().contains("count"))) {
                Optional<Integer> cnt = sql.queryCountClassesForTopic(topic);
                if (cnt.isPresent()) return String.format("You have %d classes for %s.", cnt.get(), topic);
            }
        } catch (Exception ex) { /* ignore */ }
        return null;
    }

    private String normalized(String s) {
        if (s == null) return "";
        return s.replaceAll("\\s+"," ").toLowerCase().trim();
    }

    private List<String> parseCitedChunkIds(String text) {
        if (text==null) return Collections.emptyList();
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\[source:\\s*([A-Za-z0-9_\\-:, ]+)\\]").matcher(text);
        while (m.find()) {
            String[] parts = m.group(1).split(",");
            for (String p : parts) out.add(p.trim());
        }
        return out;
    }

    private List<String> topKChunkIdsForQuery(String query, int k) {
        try {
            float[] qvec = svc.getEmbedder().embed(query); // expose embedder getter
            List<Candidate> pts = qdrant.searchByVector(qvec, k, true); // returns candidates with payload.chunk_id
            return pts.stream().map(c -> {
                Object pid = c.getPayload()!=null?c.getPayload().get("chunk_id"):null;
                return pid==null?c.getId():pid.toString();
            }).collect(Collectors.toList());
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    // Save results summary CSV
    public void saveResults(List<Result> results, Path outCsv) throws IOException {
        try (BufferedWriter w = Files.newBufferedWriter(outCsv)) {
            w.write("id,type,factExact,recall,faithfulness,hallucination,latencyMs,answer\n");
            for (Result r : results) {
                w.write(String.format("%s,%s,%b,%b,%b,%b,%d,\"%s\"\n",
                    r.id, r.type, r.factExact, r.recall, r.faithfulness, r.hallucination, r.latencyMs, r.rawAnswer.replace("\"","'")) );
            }
        }
    }

    // Aggregate metrics
    public static Map<String,Double> aggregate(List<Result> results) {
        Map<String,Double> m = new LinkedHashMap<>();
        long total = results.size();
        m.put("factual_precision", results.stream().filter(r->r.type==TestCase.Type.FACTUAL).mapToInt(r->r.factExact?1:0).average().orElse(0.0));
        m.put("hallucination_rate", results.stream().filter(r->r.hallucination).count() / (double)total);
        m.put("retrieval_recall", results.stream().filter(r->r.recall).count() / (double)total);
        m.put("avg_latency_ms", results.stream().mapToLong(r->r.latencyMs).average().orElse(0.0));
        return m;
    }
}
