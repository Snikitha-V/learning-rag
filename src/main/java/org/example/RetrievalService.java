package org.example;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RetrievalService {
    private static final Logger LOG = LoggerFactory.getLogger(RetrievalService.class);

    private final OnnxEmbedder embedder;
    private final QdrantClient qdrant;
    private final LuceneIndexService lucene;
    private final CrossEncoderScorer crossEncoder;
    private final DataFetcher dbFetcher;
    private final LLMClient llm;
    private final PromptBuilder promptBuilder;
    private final LruCache<String, float[]> embedCache = new LruCache<>(1000);
    private final LruCache<String, List<DbChunk>> retrCache = new LruCache<>(500);

    public RetrievalService(OnnxEmbedder embedder, QdrantClient qdrant, LuceneIndexService lucene, CrossEncoderScorer crossEncoder, DataFetcher dbFetcher) {
        this.embedder = embedder;
        this.qdrant = qdrant;
        this.lucene = lucene;
        this.crossEncoder = crossEncoder;
        this.dbFetcher = dbFetcher;
        this.llm = new LLMClient();
        this.promptBuilder = new PromptBuilder(
            Config.CROSS_ENCODER_ONNX_DIR,
            Config.PROMPT_MAX_TOKENS,
            Config.PROMPT_RESERVED_ANSWER,
            Config.PROMPT_OVERHEAD
        );
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

    /**
     * Full RAG pipeline with Level-7 routing: intent classification, SQL for factual,
     * RAG for semantic, hybrid for mixed queries.
     */
    public String ask(String query) throws Exception {
        // 0. Setup helpers
        IntentClassifier.Intent intent = IntentClassifier.classify(query);
        SqlService sql = new SqlService(Config.DB_URL, Config.DB_USER, Config.DB_PASS);
        LOG.info("Query intent: {}", intent);

        // For MIXED: we'll run SQL first to get authoritative facts
        DbChunk sqlChunk = null;

        if (intent == IntentClassifier.Intent.FACTUAL || intent == IntentClassifier.Intent.MIXED) {
            // Heuristic: try to extract an entity/token we can send to SQL (topic id, class id, etc.)
            String topicId = extractTopicIdFromQuery(query);
            if (topicId != null) {
                LOG.info("Extracted topicId: {}", topicId);
                // example factual SQL queries based on user phrasing
                try {
                    if (query.toLowerCase().contains("when")) {
                        Optional<Map<String,String>> range = sql.queryLearnedAtRange(topicId);
                        if (range.isPresent()) {
                            String body = sql.sqlDateRangeBody(topicId, range.get());
                            sqlChunk = sql.buildSqlChunk("learned_range_" + topicId, "SQL_RESULT: learned_at", body);
                        }
                    } else if (query.toLowerCase().contains("how many") || query.toLowerCase().contains("count")) {
                        Optional<Integer> cnt = sql.queryCountClassesForTopic(topicId);
                        if (cnt.isPresent()) {
                            String body = sql.sqlCountBody(topicId, cnt.get());
                            sqlChunk = sql.buildSqlChunk("count_classes_" + topicId, "SQL_RESULT: counts", body);
                        }
                    }
                } catch (Exception e) {
                    // SQL query failed (schema mismatch, etc.) - fall back to RAG
                    LOG.warn("SQL query failed, falling back to RAG: {}", e.getMessage());
                    sqlChunk = null;
                }
                // add more SQL cases here as required
            }
        }

        // If FACTUAL and we got a SQL result -> return deterministic answer (plus optional context)
        if (intent == IntentClassifier.Intent.FACTUAL) {
            if (sqlChunk != null) {
                // Build a short user-facing deterministic answer from SQL result
                String deterministicAnswer = extractShortAnswerFromSqlChunk(sqlChunk);
                // Optionally retrieve a small RAG context to provide explanation
                List<DbChunk> support = Collections.emptyList();
                if (shouldAttachRagContextForFactual()) {
                    support = retrieveTopContextForQuery(query, 3);
                }
                // Assemble final output: deterministic answer + context (if any)
                StringBuilder sb = new StringBuilder();
                sb.append(deterministicAnswer).append("\n");
                if (!support.isEmpty()) {
                    sb.append("\nContext:\n");
                    for (DbChunk c : support) {
                        sb.append("- ").append(c.getChunkId()).append(": ").append(snippet(c.getText())).append("\n");
                    }
                }
                return sb.toString();
            } else {
                // No SQL result: fall back to RAG retrieval and only return if evidence strong
                List<Candidate> candidates = denseRetrieveCandidates(query);
                double topScore = candidates.isEmpty() ? 0.0 : candidates.get(0).score;
                if (topScore < Config.RAG_SCORE_FALLBACK_THRESHOLD) {
                    return "I don't have that information in your database.";
                }
                // else run full RAG pipeline to generate explanation
                List<DbChunk> context = retrieve(query);
                String prompt = promptBuilder.buildPrompt(context, query, Config.CONTEXT_K);
                long t0 = System.nanoTime();
                String ans = llm.generate(prompt, 300);
                LOG.info("[timing] llm generate ms={}", (System.nanoTime() - t0) / 1_000_000);
                return ans;
            }
        }

        // If SEMANTIC: run the full RAG pipeline (no SQL)
        if (intent == IntentClassifier.Intent.SEMANTIC) {
            List<DbChunk> context = retrieve(query);
            String prompt = promptBuilder.buildPrompt(context, query, Config.CONTEXT_K);
            long t0 = System.nanoTime();
            String ans = llm.generate(prompt, 300);
            LOG.info("[timing] llm generate ms={}", (System.nanoTime() - t0) / 1_000_000);
            return ans;
        }

        // If MIXED: we have SQL chunk (may be null if extraction failed)
        if (intent == IntentClassifier.Intent.MIXED) {
            List<DbChunk> context = retrieve(query);
            // insert SQL chunk at top if exists
            if (sqlChunk != null) {
                List<DbChunk> merged = new ArrayList<>();
                merged.add(sqlChunk);
                merged.addAll(context);
                context = merged;
            }
            String prompt = promptBuilder.buildPrompt(context, query, Config.CONTEXT_K);
            long t0 = System.nanoTime();
            String ans = llm.generate(prompt, 300);
            LOG.info("[timing] llm generate ms={}", (System.nanoTime() - t0) / 1_000_000);
            return ans;
        }

        // Default fallback: run RAG
        List<DbChunk> context = retrieve(query);
        String prompt = promptBuilder.buildPrompt(context, query, Config.CONTEXT_K);
        long t0 = System.nanoTime();
        String ans = llm.generate(prompt, 300);
        LOG.info("[timing] llm generate ms={}", (System.nanoTime() - t0) / 1_000_000);
        return ans;
    }

    // ========== Helper methods for Level-7 routing ==========

    /**
     * Extract topic ID pattern like C<number>-T<number> from query.
     */
    private String extractTopicIdFromQuery(String q) {
        if (q == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern
            .compile("\\bC\\d+-T\\d+\\b", java.util.regex.Pattern.CASE_INSENSITIVE)
            .matcher(q);
        if (m.find()) return m.group(0).toUpperCase();
        return null;
    }

    /**
     * Should we attach small RAG context for factual answers? Configurable policy.
     */
    private boolean shouldAttachRagContextForFactual() {
        return true; // or Config.SHOW_RAG_FOR_FACTS
    }

    /**
     * Retrieve small top-k support context without SQL chunk insertion.
     */
    private List<DbChunk> retrieveTopContextForQuery(String query, int k) throws Exception {
        List<DbChunk> ctx = retrieve(query);
        if (ctx.size() > k) return ctx.subList(0, k);
        return ctx;
    }

    /**
     * Dense-only candidate fetch to check score for fallback threshold.
     */
    private List<Candidate> denseRetrieveCandidates(String query) throws Exception {
        float[] qvec = embedder.embed(query);
        return qdrant.search(qvec, 5, Config.QDRANT_EF);
    }

    /**
     * Extract a natural language answer from SQL chunk.
     * Parses the SQL result body and formats it in a human-friendly way.
     */
    private String extractShortAnswerFromSqlChunk(DbChunk sqlChunk) {
        String text = sqlChunk.getText();
        if (text == null || text.isEmpty()) return "";
        
        // Parse the SQL result body
        // Format: "SQL_RESULT for topic=C1-T1\nearliest: 2025-06-13T11:00\nlatest: 2025-08-02T11:00"
        // Or: "SQL_RESULT for topic=C1-T1\nTotal classes: 5"
        
        String topicCode = null;
        String earliest = null;
        String latest = null;
        Integer totalClasses = null;
        
        for (String line : text.split("\\r?\\n")) {
            line = line.trim();
            if (line.startsWith("SQL_RESULT for topic=")) {
                topicCode = line.substring("SQL_RESULT for topic=".length()).trim();
            } else if (line.startsWith("earliest:")) {
                earliest = formatDateTime(line.substring("earliest:".length()).trim());
            } else if (line.startsWith("latest:")) {
                latest = formatDateTime(line.substring("latest:".length()).trim());
            } else if (line.startsWith("Total classes:")) {
                try {
                    totalClasses = Integer.parseInt(line.substring("Total classes:".length()).trim());
                } catch (NumberFormatException ignored) {}
            }
        }
        
        // Build natural language response
        StringBuilder sb = new StringBuilder();
        
        if (earliest != null || latest != null) {
            // Date range query
            if (earliest != null && latest != null) {
                if (earliest.equals(latest)) {
                    sb.append("You learned ").append(topicCode != null ? topicCode : "this topic")
                      .append(" on ").append(earliest).append(".");
                } else {
                    sb.append("You learned ").append(topicCode != null ? topicCode : "this topic")
                      .append(" between ").append(earliest)
                      .append(" and ").append(latest).append(".");
                }
            } else if (earliest != null) {
                sb.append("You first learned ").append(topicCode != null ? topicCode : "this topic")
                  .append(" on ").append(earliest).append(".");
            } else {
                sb.append("You last learned ").append(topicCode != null ? topicCode : "this topic")
                  .append(" on ").append(latest).append(".");
            }
        } else if (totalClasses != null) {
            // Count query
            sb.append("You have ").append(totalClasses).append(" class")
              .append(totalClasses == 1 ? "" : "es")
              .append(" for ").append(topicCode != null ? topicCode : "this topic").append(".");
        } else {
            // Fallback: return raw text snippet
            return snippet(text, 150);
        }
        
        return sb.toString();
    }
    
    /**
     * Format ISO datetime string to a more readable format.
     * Input: "2025-06-13T11:00" or "2025-06-13T11:00:00"
     * Output: "June 13, 2025"
     */
    private String formatDateTime(String isoDateTime) {
        if (isoDateTime == null || isoDateTime.isEmpty()) return isoDateTime;
        try {
            // Parse ISO format
            java.time.LocalDateTime dt = java.time.LocalDateTime.parse(isoDateTime);
            // Format to readable date
            return dt.format(java.time.format.DateTimeFormatter.ofPattern("MMMM d, yyyy"));
        } catch (Exception e) {
            // If parsing fails, return as-is
            return isoDateTime;
        }
    }

    /**
     * Create a short snippet of text for display.
     */
    private String snippet(String t) {
        return snippet(t, 120);
    }
    
    private String snippet(String t, int maxLen) {
        if (t == null) return "";
        int n = Math.min(maxLen, t.length());
        return t.substring(0, n).replaceAll("\\r?\\n", " ") + (t.length() > n ? "..." : "");
    }

    /**
     * Build prompt for the LLM with context and query.
     */
    private String buildPrompt(String query, String context) {
        return "[INST] You are a helpful learning assistant. Use the following context from the user's learning history to answer their question.\n\n" +
               "CONTEXT:\n" + context + "\n" +
               "QUESTION: " + query + "\n\n" +
               "Answer based on the context above. If the context doesn't contain relevant information, say so. [/INST]";
    }
}
