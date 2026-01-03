package org.example;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;
import org.example.dto.ConversationTurn;
import org.example.llm.LLMFactory;
import org.example.llm.LLMProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class RetrievalService {
    private static final Logger LOG = LoggerFactory.getLogger(RetrievalService.class);
    private static final int HISTORY_MAX_ENTRIES = 6;
    private static final int HISTORY_MAX_CHARS_PER_ENTRY = 800;

    private final OnnxEmbedder embedder;
    private final QdrantClient qdrant;
    private final LuceneIndexService lucene;
    private final CrossEncoderScorer crossEncoder;
    private final DataFetcher dbFetcher;
    private final LLMProvider llm;  // Now uses interface for plug-in support
    private final PromptBuilder promptBuilder;
    private final LruCache<String, float[]> embedCache = new LruCache<>(1000);
    private final LruCache<String, List<DbChunk>> retrCache = new LruCache<>(500);

    public RetrievalService(OnnxEmbedder embedder, QdrantClient qdrant, LuceneIndexService lucene, CrossEncoderScorer crossEncoder, DataFetcher dbFetcher) {
        this.embedder = embedder;
        this.qdrant = qdrant;
        this.lucene = lucene;
        this.crossEncoder = crossEncoder;
        this.dbFetcher = dbFetcher;
        this.llm = LLMFactory.createProvider();  // Factory-based LLM selection
        this.promptBuilder = new PromptBuilder(
            Config.CROSS_ENCODER_ONNX_DIR,
            Config.PROMPT_MAX_TOKENS,
            Config.PROMPT_RESERVED_ANSWER,
            Config.PROMPT_OVERHEAD
        );
    }

    /**
     * Get the embedder for external use (e.g., evaluation).
     */
    public OnnxEmbedder getEmbedder() {
        return embedder;
    }

    /**
     * Factory method to create a default RetrievalService with standard config.
     */
    public static RetrievalService createDefault() throws Exception {
        OnnxEmbedder embedder = new OnnxEmbedder(
            Config.EMBED_MODEL_PATH + "/model.onnx",
            Config.EMBED_MODEL_PATH,
            384
        );
        QdrantClient qdrant = new QdrantClient(Config.QDRANT_URL, Config.QDRANT_COLLECTION);
        LuceneIndexService lucene = new LuceneIndexService(Config.LUCENE_INDEX_DIR);
        CrossEncoderScorer cross = new CrossEncoderScorer(embedder);
        DataFetcher db = new DataFetcher();
        return new RetrievalService(embedder, qdrant, lucene, cross, db);
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
     * RAG-only pipeline (no SQL) for comparison testing.
     * Uses pure semantic retrieval regardless of intent.
     */
    public QueryResult askRagOnly(String query) throws Exception {
        QueryResult result = new QueryResult();
        
        // Check for greetings first
        if (IntentClassifier.isGreeting(query)) {
            result.setAnswer("Hello! How can I help you with your learning topics today?");
            result.setIntent("GREETING");
            result.setConfidence("high");
            LOG.info("Query intent: GREETING (rag-only mode)");
            return result;
        }
        
        LOG.info("RAG-ONLY mode: skipping SQL, using pure semantic retrieval");
        result.setIntent("RAG-ONLY");
        
        // Track retrieval chain
        List<Map<String, Object>> retrievalChain = new ArrayList<>();
        List<String> sourceIds = new ArrayList<>();
        
        // Run dense + BM25 + cross-encoder (full RAG pipeline, no SQL)
        List<Candidate> candidates = denseRetrieveCandidates(query);
        for (Candidate c : candidates) {
            Map<String, Object> entry = new HashMap<>();
            entry.put("id", c.id);
            entry.put("score", c.score);
            retrievalChain.add(entry);
        }
        result.setRetrievalChain(retrievalChain);
        
        double topScore = candidates.isEmpty() ? 0.0 : candidates.get(0).score;
        
        // Retrieve context using full hybrid pipeline
        List<DbChunk> context = retrieve(query);
        for (DbChunk c : context) sourceIds.add(c.getChunkId());
        
        // Generate answer with LLM
        String prompt = promptBuilder.buildPrompt(context, query, Config.CONTEXT_K);
        long t0 = System.nanoTime();
        String ans = llm.generate(prompt, 300);
        LOG.info("[timing] llm generate ms={}", (System.nanoTime() - t0) / 1_000_000);
        
        result.setAnswer(ans);
        result.setSources(sourceIds);
        result.setConfidence(topScore > 0.7 ? "high" : topScore > 0.4 ? "medium" : "low");
        result.setSql(null); // No SQL in rag-only mode
        
        return result;
    }

    /**
     * Full RAG pipeline with metadata for UI display.
     * Returns structured QueryResult with answer, sources, SQL, retrieval chain, etc.
     */
    public QueryResult askWithMetadata(String query, List<ConversationTurn> history) throws Exception {
        QueryResult result = new QueryResult();
        final String conversationHistory = buildConversationHistory(history);
        
        // 0. Check for greetings first (no RAG needed)
        if (IntentClassifier.isGreeting(query)) {
            result.setAnswer("Hello! How can I help you with your learning topics today?");
            result.setIntent("GREETING");
            result.setConfidence("high");
            LOG.info("Query intent: GREETING");
            return result;
        }
        
        // 1. Setup helpers
        IntentClassifier.Intent intent = IntentClassifier.classify(query);
        SqlService sql = new SqlService(Config.DB_URL, Config.DB_USER, Config.DB_PASS);
        LOG.info("Query intent: {}", intent);
        result.setIntent(intent.name());

        // Track retrieval chain for "why" display
        List<Map<String, Object>> retrievalChain = new ArrayList<>();
        List<String> sourceIds = new ArrayList<>();
        String sqlText = null;

        // For MIXED: we'll run SQL first to get authoritative facts
        DbChunk sqlChunk = null;

        if (intent == IntentClassifier.Intent.FACTUAL || intent == IntentClassifier.Intent.MIXED) {
            // Check for listing queries first (no topic ID needed)
            String qLower = query.toLowerCase();
            try {
                if (qLower.contains("course") && (qLower.contains("what") || qLower.contains("list") || qLower.contains("all"))) {
                    List<Map<String,String>> courses = sql.listCourses();
                    if (!courses.isEmpty()) {
                        StringBuilder body = new StringBuilder("SQL_RESULT: All Courses\n");
                        for (Map<String,String> c : courses) {
                            body.append("- ").append(c.get("code")).append(": ").append(c.get("title")).append("\n");
                        }
                        sqlChunk = sql.buildSqlChunk("list_courses", "SQL_RESULT: courses", body.toString());
                        sqlText = "SELECT code, title FROM courses";
                    }
                } else if (qLower.contains("topic") && (qLower.contains("what") || qLower.contains("list") || qLower.contains("all"))) {
                    List<Map<String,String>> topics = sql.listTopics();
                    if (!topics.isEmpty()) {
                        StringBuilder body = new StringBuilder("SQL_RESULT: All Topics\n");
                        for (Map<String,String> t : topics) {
                            body.append("- ").append(t.get("code")).append(": ").append(t.get("title")).append("\n");
                        }
                        sqlChunk = sql.buildSqlChunk("list_topics", "SQL_RESULT: topics", body.toString());
                        sqlText = "SELECT topic_code, title FROM topics";
                    }
                }
            } catch (Exception e) {
                LOG.warn("SQL list query failed: {}", e.getMessage());
            }

            // If no listing match, try topic-based queries
            if (sqlChunk == null) {
                String topicId = extractTopicIdFromQuery(query);
                if (topicId != null) {
                    LOG.info("Extracted topicId: {}", topicId);
                    try {
                        if (qLower.contains("when")) {
                            Optional<Map<String,String>> range = sql.queryLearnedAtRange(topicId);
                            if (range.isPresent()) {
                                String body = sql.sqlDateRangeBody(topicId, range.get());
                                sqlChunk = sql.buildSqlChunk("learned_range_" + topicId, "SQL_RESULT: learned_at", body);
                                sqlText = "SELECT MIN(learned_at), MAX(learned_at) FROM classes WHERE topic_id='" + topicId + "'";
                            }
                        } else if (qLower.contains("how many") || qLower.contains("count")) {
                            Optional<Integer> cnt = sql.queryCountClassesForTopic(topicId);
                            if (cnt.isPresent()) {
                                String body = sql.sqlCountBody(topicId, cnt.get());
                                sqlChunk = sql.buildSqlChunk("count_classes_" + topicId, "SQL_RESULT: counts", body);
                                sqlText = "SELECT COUNT(*) FROM classes WHERE topic_id='" + topicId + "'";
                            }
                        }
                    } catch (Exception e) {
                        LOG.warn("SQL query failed, falling back to RAG: {}", e.getMessage());
                        sqlChunk = null;
                    }
                }
            }
        }

        result.setSql(sqlText);

        // FACTUAL path
        if (intent == IntentClassifier.Intent.FACTUAL) {
                if (sqlChunk != null) {
                // Treat SQL chunk as a retrievable candidate: prepend it to RAG context
                // and run cross-encoder reranking so SQL gets ranked among semantic results.
                sourceIds.add(sqlChunk.getChunkId());

                List<DbChunk> context;
                if (shouldAttachRagContextForFactual()) {
                    // Pull a larger RAG context to rerank with SQL chunk
                    List<DbChunk> ragContext = retrieveTopContextForQuery(query, Math.max(Config.RERANK_TOP_N, 3));
                    List<DbChunk> merged = new ArrayList<>();
                    merged.add(sqlChunk);
                    merged.addAll(ragContext);

                    // Score with cross-encoder and sort
                    Map<String, Float> rerankScores = crossEncoder.scoreBatch(query, merged);
                    merged.sort((a,b) -> Float.compare(rerankScores.getOrDefault(b.getChunkId(), 0f), rerankScores.getOrDefault(a.getChunkId(), 0f)));

                    // Pick final top-k context
                    context = merged.stream().limit(Config.CONTEXT_K).collect(Collectors.toList());
                    for (DbChunk c : context) sourceIds.add(c.getChunkId());
                } else {
                    // No RAG context requested: just use SQL chunk
                    context = new ArrayList<>();
                    context.add(sqlChunk);
                }

                String prompt = promptBuilder.buildPrompt(context, query, Config.CONTEXT_K, conversationHistory);
                long t0 = System.nanoTime();
                String ans = llm.generate(prompt, 300);
                LOG.info("[timing] llm generate ms={}", (System.nanoTime() - t0) / 1_000_000);

                result.setAnswer(ans);
                result.setSources(sourceIds);
                result.setConfidence("high");
                return result;
            } else {
                List<Candidate> candidates = denseRetrieveCandidates(query);
                for (Candidate c : candidates) {
                    Map<String, Object> entry = new HashMap<>();
                    entry.put("id", c.id);
                    entry.put("score", c.score);
                    retrievalChain.add(entry);
                }
                result.setRetrievalChain(retrievalChain);
                
                double topScore = candidates.isEmpty() ? 0.0 : candidates.get(0).score;
                if (topScore < Config.RAG_SCORE_FALLBACK_THRESHOLD) {
                    // Borderline / low semantic evidence: rather than a terse "don't have it",
                    // generate a RAG-based answer but mark it as low confidence and include a disclaimer.
                    List<DbChunk> ctx = retrieve(query);
                    for (DbChunk c : ctx) sourceIds.add(c.getChunkId());

                    String prompt = promptBuilder.buildLenientPrompt(ctx, query, Config.CONTEXT_K, conversationHistory);
                    long t0 = System.nanoTime();
                    String ans = llm.generate(prompt, 300);
                    LOG.info("[timing] llm generate ms={}", (System.nanoTime() - t0) / 1_000_000);

                    String finalAns = "I couldn't find a matching authoritative record in your database. " +
                                      "Based on semantic evidence (low confidence), here is what I found:\n\n" + ans;
                    result.setAnswer(finalAns);
                    result.setSources(sourceIds);
                    result.setConfidence("low");
                    result.setRetrievalChain(retrievalChain);
                    return result;
                }

                List<DbChunk> context = retrieve(query);
                for (DbChunk c : context) sourceIds.add(c.getChunkId());

                String prompt = promptBuilder.buildPrompt(context, query, Config.CONTEXT_K, conversationHistory);
                long t0 = System.nanoTime();
                String ans = llm.generate(prompt, 300);
                LOG.info("[timing] llm generate ms={}", (System.nanoTime() - t0) / 1_000_000);

                result.setAnswer(ans);
                result.setSources(sourceIds);
                result.setConfidence(topScore > 0.7 ? "high" : "medium");
                return result;
            }
        }

        // SEMANTIC path
        if (intent == IntentClassifier.Intent.SEMANTIC) {
            List<Candidate> candidates = denseRetrieveCandidates(query);
            for (Candidate c : candidates) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("id", c.id);
                entry.put("score", c.score);
                retrievalChain.add(entry);
            }
            result.setRetrievalChain(retrievalChain);
            
            List<DbChunk> context = retrieve(query);
            for (DbChunk c : context) sourceIds.add(c.getChunkId());
            
            String prompt = promptBuilder.buildPrompt(context, query, Config.CONTEXT_K, conversationHistory);
            long t0 = System.nanoTime();
            String ans = llm.generate(prompt, 300);
            LOG.info("[timing] llm generate ms={}", (System.nanoTime() - t0) / 1_000_000);
            
            double topScore = candidates.isEmpty() ? 0.0 : candidates.get(0).score;
            result.setAnswer(ans);
            result.setSources(sourceIds);
            result.setConfidence(topScore > 0.7 ? "high" : topScore > 0.4 ? "medium" : "low");
            return result;
        }

        // MIXED path
        if (intent == IntentClassifier.Intent.MIXED) {
            List<Candidate> candidates = denseRetrieveCandidates(query);
            for (Candidate c : candidates) {
                Map<String, Object> entry = new HashMap<>();
                entry.put("id", c.id);
                entry.put("score", c.score);
                retrievalChain.add(entry);
            }
            result.setRetrievalChain(retrievalChain);
            
            List<DbChunk> context = retrieve(query);
            if (sqlChunk != null) {
                List<DbChunk> merged = new ArrayList<>();
                merged.add(sqlChunk);
                sourceIds.add(sqlChunk.getChunkId());
                merged.addAll(context);
                context = merged;
            }
            for (DbChunk c : context) {
                if (!sourceIds.contains(c.getChunkId())) sourceIds.add(c.getChunkId());
            }
            
            String prompt = promptBuilder.buildPrompt(context, query, Config.CONTEXT_K, conversationHistory);
            long t0 = System.nanoTime();
            String ans = llm.generate(prompt, 300);
            LOG.info("[timing] llm generate ms={}", (System.nanoTime() - t0) / 1_000_000);
            
            result.setAnswer(ans);
            result.setSources(sourceIds);
            result.setConfidence(sqlChunk != null ? "high" : "medium");
            return result;
        }

        // Default fallback
        List<DbChunk> context = retrieve(query);
        for (DbChunk c : context) sourceIds.add(c.getChunkId());
        
        String prompt = promptBuilder.buildPrompt(context, query, Config.CONTEXT_K, conversationHistory);
        long t0 = System.nanoTime();
        String ans = llm.generate(prompt, 300);
        LOG.info("[timing] llm generate ms={}", (System.nanoTime() - t0) / 1_000_000);
        
        result.setAnswer(ans);
        result.setSources(sourceIds);
        result.setConfidence("medium");
        return result;
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
            // Check for listing queries first (no topic ID needed)
            String qLower = query.toLowerCase();
            try {
                if (qLower.contains("course") && (qLower.contains("what") || qLower.contains("list") || qLower.contains("all"))) {
                    List<Map<String,String>> courses = sql.listCourses();
                    if (!courses.isEmpty()) {
                        StringBuilder body = new StringBuilder("SQL_RESULT: All Courses\n");
                        for (Map<String,String> c : courses) {
                            body.append("- ").append(c.get("code")).append(": ").append(c.get("title")).append("\n");
                        }
                        sqlChunk = sql.buildSqlChunk("list_courses", "SQL_RESULT: courses", body.toString());
                    }
                } else if (qLower.contains("topic") && (qLower.contains("what") || qLower.contains("list") || qLower.contains("all"))) {
                    List<Map<String,String>> topics = sql.listTopics();
                    if (!topics.isEmpty()) {
                        StringBuilder body = new StringBuilder("SQL_RESULT: All Topics\n");
                        for (Map<String,String> t : topics) {
                            body.append("- ").append(t.get("code")).append(": ").append(t.get("title")).append("\n");
                        }
                        sqlChunk = sql.buildSqlChunk("list_topics", "SQL_RESULT: topics", body.toString());
                    }
                }
            } catch (Exception e) {
                LOG.warn("SQL list query failed: {}", e.getMessage());
            }

            // If no listing match, try topic-based queries
            if (sqlChunk == null) {
                String topicId = extractTopicIdFromQuery(query);
                if (topicId != null) {
                    LOG.info("Extracted topicId: {}", topicId);
                    // example factual SQL queries based on user phrasing
                    try {
                        if (qLower.contains("when")) {
                            Optional<Map<String,String>> range = sql.queryLearnedAtRange(topicId);
                            if (range.isPresent()) {
                                String body = sql.sqlDateRangeBody(topicId, range.get());
                                sqlChunk = sql.buildSqlChunk("learned_range_" + topicId, "SQL_RESULT: learned_at", body);
                            }
                        } else if (qLower.contains("how many") || qLower.contains("count")) {
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
        }

        // If FACTUAL and we got a SQL result -> use SQL data as context for LLM
        if (intent == IntentClassifier.Intent.FACTUAL) {
            if (sqlChunk != null) {
                // Use SQL result as primary context, optionally add some RAG context
                List<DbChunk> context = new ArrayList<>();
                context.add(sqlChunk);  // Add SQL result as context chunk
                
                // Optionally retrieve a small RAG context to provide additional info
                if (shouldAttachRagContextForFactual()) {
                    List<DbChunk> ragContext = retrieveTopContextForQuery(query, 3);
                    context.addAll(ragContext);
                }
                
                // Pass to LLM to generate natural answer
                String prompt = promptBuilder.buildPrompt(context, query, Config.CONTEXT_K);
                long t0 = System.nanoTime();
                String ans = llm.generate(prompt, 300);
                LOG.info("[timing] llm generate ms={}", (System.nanoTime() - t0) / 1_000_000);
                return ans;
            } else {
                // No SQL result: fall back to RAG retrieval and only return if evidence strong
                List<Candidate> candidates = denseRetrieveCandidates(query);
                double topScore = candidates.isEmpty() ? 0.0 : candidates.get(0).score;
                if (topScore < Config.RAG_SCORE_FALLBACK_THRESHOLD) {
                    // Low semantic evidence: produce a best-effort RAG answer with disclaimer (lenient prompt)
                    List<DbChunk> ctx = retrieve(query);
                    String prompt = promptBuilder.buildLenientPrompt(ctx, query, Config.CONTEXT_K);
                    long t0 = System.nanoTime();
                    String ans = llm.generate(prompt, 300);
                    LOG.info("[timing] llm generate ms={}", (System.nanoTime() - t0) / 1_000_000);
                    return "I couldn't find a matching authoritative record in your database. Based on semantic evidence (low confidence), here is what I found:\n\n" + ans;
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
            // insert SQL chunk and rerank with cross-encoder if it exists
            if (sqlChunk != null) {
                List<DbChunk> merged = new ArrayList<>();
                merged.add(sqlChunk);
                merged.addAll(context);

                // Score merged set with cross-encoder and sort
                Map<String, Float> rerankScores = crossEncoder.scoreBatch(query, merged);
                merged.sort((a,b) -> Float.compare(rerankScores.getOrDefault(b.getChunkId(), 0f), rerankScores.getOrDefault(a.getChunkId(), 0f)));

                // Limit to final rerank size (then promptBuilder will trim to CONTEXT_K)
                context = merged.stream().limit(Math.max(Config.RERANK_FINAL_N, Config.CONTEXT_K)).collect(Collectors.toList());
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
    String extractTopicIdFromQuery(String q) {
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

    private String buildConversationHistory(List<ConversationTurn> history) {
        if (history == null || history.isEmpty()) return null;
        int start = Math.max(0, history.size() - HISTORY_MAX_ENTRIES);
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < history.size(); i++) {
            ConversationTurn turn = history.get(i);
            if (turn == null) continue;
            String role = turn.getRole();
            String label = "User";
            if (role != null) {
                if (role.equalsIgnoreCase("assistant")) label = "Assistant";
                else if (role.equalsIgnoreCase("user")) label = "User";
                else label = role;
            }
            String content = turn.getContent();
            if (content == null) continue;
            String trimmed = content.trim();
            if (trimmed.isEmpty()) continue;
            if (trimmed.length() > HISTORY_MAX_CHARS_PER_ENTRY) {
                trimmed = trimmed.substring(trimmed.length() - HISTORY_MAX_CHARS_PER_ENTRY);
            }
            if (sb.length() > 0) sb.append("\n");
            sb.append(label).append(": ").append(trimmed);
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? null : result;
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
