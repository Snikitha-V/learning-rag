package org.example;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

public class RetrievalService {
    private final OnnxEmbedder embedder;
    private final QdrantClient qdrant;
    private final LuceneIndexService lucene;
    private final CrossEncoderScorer crossEncoder;
    private final DataFetcher dbFetcher;
    private final LruCache<String, float[]> embedCache = new LruCache<>(1000);
    private final LruCache<String, List<DbChunk>> retrCache = new LruCache<>(500);

    public RetrievalService(OnnxEmbedder embedder, QdrantClient qdrant, LuceneIndexService lucene, CrossEncoderScorer crossEncoder, DataFetcher dbFetcher) {
        this.embedder = embedder;
        this.qdrant = qdrant;
        this.lucene = lucene;
        this.crossEncoder = crossEncoder;
        this.dbFetcher = dbFetcher;
    }

    /**
     * Merge dense and lexical candidates, dedupe by chunk_id, fetch missing vectors.
     */
    public static List<Candidate> mergeAndDedupe(List<Candidate> dense, List<String> lexIds, QdrantClient qdrant) throws IOException {
        LinkedHashMap<String, Candidate> map = new LinkedHashMap<>();
        // Key by chunk_id from payload (for dense results) or id (for lex results)
        for (Candidate c : dense) {
            String key = (c.payload != null && c.payload.get("chunk_id") != null) 
                    ? c.payload.get("chunk_id").toString() 
                    : c.id;
            map.putIfAbsent(key, c);
        }
        for (String id : lexIds) {
            map.computeIfAbsent(id, k -> {
                Candidate shell = new Candidate();
                shell.id = k;
                shell.score = 0;
                shell.vector = null;
                shell.payload = null;
                return shell;
            });
        }
        // Fetch missing vectors by chunk_id
        List<String> missing = map.values().stream()
                .filter(c -> c.vector == null)
                .map(c -> c.id)
                .collect(Collectors.toList());
        if (!missing.isEmpty()) {
            Map<String, Candidate> fetched = qdrant.getPointsByChunkIds(missing);
            for (Candidate c : map.values()) {
                String cid = c.id;
                if (c.vector == null && fetched.containsKey(cid)) {
                    c.vector = fetched.get(cid).vector;
                    c.payload = fetched.get(cid).payload;
                }
            }
        }
        return new ArrayList<>(map.values());
    }

    public List<DbChunk> retrieve(String query) throws Exception {
        String qkey = query.trim().toLowerCase();

        // Check retrieval cache first
        List<DbChunk> cached = retrCache.get(qkey);
        if (cached != null) {
            return cached;
        }

        // 1) embedding with cache
        long t0 = System.nanoTime();
        float[] qvec = embedCache.get(qkey);
        if (qvec == null) {
            qvec = embedder.embed(query);
            embedCache.put(qkey, qvec);
        }
        System.out.println("[timing] embed ms=" + ((System.nanoTime() - t0) / 1_000_000));

        // 2) dense retrieval with retry
        t0 = System.nanoTime();
        final float[] qvecFinal = qvec;
        List<Candidate> dense = RetryUtil.withRetry(
                () -> qdrant.search(qvecFinal, Config.TOPK_DENSE, Config.QDRANT_EF), 3);
        System.out.println("[timing] qdrant search ms=" + ((System.nanoTime() - t0) / 1_000_000));

        // 3) BM25 lexical retrieval (may be empty if lucene index missing)
        t0 = System.nanoTime();
        List<String> lexIds;
        try {
            lexIds = lucene.search(query, Config.TOPK_LEX);
        } catch (Exception e) {
            // if lucene not built, ignore
            lexIds = Collections.emptyList();
        }
        System.out.println("[timing] bm25 search ms=" + ((System.nanoTime() - t0) / 1_000_000));

        // 4) merge & dedupe by chunk_id, fetch missing vectors
        t0 = System.nanoTime();
        List<Candidate> merged = mergeAndDedupe(dense, lexIds, qdrant);
        System.out.println("[timing] merge+dedupe ms=" + ((System.nanoTime() - t0) / 1_000_000));

        // 5) MMR pick
        t0 = System.nanoTime();
        List<Candidate> mmrSelected = MMR.rerank(merged, qvec, Config.MMR_FINAL_SIZE, Config.MMR_LAMBDA);
        System.out.println("[timing] mmr ms=" + ((System.nanoTime() - t0) / 1_000_000));

        // 7) fetch full chunk rows from DB using chunk_id from payload (not the Qdrant UUID)
        t0 = System.nanoTime();
        List<String> chunkIds = mmrSelected.stream()
                .map(c -> c.payload != null && c.payload.containsKey("chunk_id") 
                        ? c.payload.get("chunk_id").toString() 
                        : c.id)
                .collect(Collectors.toList());
        Map<String, DbChunk> rows = RetryUtil.withRetry(() -> dbFetcher.fetchChunks(chunkIds), 3);
        System.out.println("[timing] db fetch ms=" + ((System.nanoTime() - t0) / 1_000_000));

        // 8) cross-encoder rerank on mmrSelected (top RERANK_TOP_N)
        t0 = System.nanoTime();
        List<DbChunk> mmrChunksOrdered = mmrSelected.stream()
                .map(c -> {
                    String chunkId = c.payload != null && c.payload.containsKey("chunk_id") 
                            ? c.payload.get("chunk_id").toString() 
                            : c.id;
                    return rows.get(chunkId);
                })
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        List<DbChunk> toRerank = mmrChunksOrdered.size() > Config.RERANK_TOP_N ? mmrChunksOrdered.subList(0, Config.RERANK_TOP_N) : mmrChunksOrdered;

        Map<String, Float> rerankScores = crossEncoder.scoreBatch(query, toRerank);
        System.out.println("[timing] cross-encoder ms=" + ((System.nanoTime() - t0) / 1_000_000));

        // sort by score desc
        toRerank.sort((a,b) -> Float.compare(rerankScores.getOrDefault(b.getChunkId(), 0f), rerankScores.getOrDefault(a.getChunkId(), 0f)));

        // 9) final top N -> pick context_k
        List<DbChunk> finalList = toRerank.stream().limit(Config.RERANK_FINAL_N).collect(Collectors.toList());
        List<DbChunk> context = finalList.stream().limit(Config.CONTEXT_K).collect(Collectors.toList());

        // Cache the result
        retrCache.put(qkey, context);

        return context;
    }

    /**
     * Assemble context string from chunks with head+tail trimming.
     */
    public static String assembleContext(List<DbChunk> chunks, int contextK, int charBudget) {
        StringBuilder sb = new StringBuilder();
        int used = 0;
        for (DbChunk c : chunks.subList(0, Math.min(contextK, chunks.size()))) {
            String t = c.getText() == null ? "" : c.getText();
            int take = Math.min(t.length(), Math.max(100, charBudget - used));
            String block = t.length() <= take ? t : (t.substring(0, take/2) + "\n...\n" + t.substring(Math.max(0, t.length()-take/2)));
            sb.append("SOURCE: ").append(c.getChunkId()).append(" | ").append(c.getTitle()).append("\n").append(block).append("\n\n");
            used += block.length();
            if (used >= charBudget) break;
        }
        return sb.toString();
    }
}
