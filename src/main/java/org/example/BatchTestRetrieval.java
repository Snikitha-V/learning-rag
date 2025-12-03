package org.example;

import java.io.BufferedReader;
import java.io.FileReader;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

/**
 * Batch test runner: reads queries from tests/queries.txt and validates retrieval.
 */
public class BatchTestRetrieval {

    public static void main(String[] args) throws Exception {
        // Load components
        System.out.println("Loading embedder...");
        OnnxEmbedder embedder = new OnnxEmbedder(
                Config.EMBED_MODEL_PATH + "/model.onnx",
                Config.EMBED_MODEL_PATH,
                384
        );

        QdrantClient qdrant = new QdrantClient(Config.QDRANT_URL, Config.QDRANT_COLLECTION);
        LuceneIndexService lucene = new LuceneIndexService(Config.LUCENE_INDEX_DIR);
        CrossEncoderScorer cross = new CrossEncoderScorer(embedder);
        DataFetcher db = new DataFetcher();

        RetrievalService svc = new RetrievalService(embedder, qdrant, lucene, cross, db);

        // Read queries
        Path queriesFile = Paths.get("tests/queries.txt");
        List<String> queries = new ArrayList<>();
        try (BufferedReader br = new BufferedReader(new FileReader(queriesFile.toFile()))) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty() && !line.startsWith("#")) {
                    queries.add(line);
                }
            }
        }

        System.out.println("Loaded " + queries.size() + " queries from " + queriesFile);
        System.out.println("=".repeat(60));

        int passed = 0;
        int failed = 0;
        long totalMs = 0;

        for (int i = 0; i < queries.size(); i++) {
            String query = queries.get(i);
            long t0 = System.nanoTime();

            try {
                List<DbChunk> results = svc.retrieve(query);
                long ms = (System.nanoTime() - t0) / 1_000_000;
                totalMs += ms;

                // Basic validation: should return at least 1 result
                boolean hasResults = results != null && !results.isEmpty();

                // Check if any result text contains keywords from query
                boolean keywordMatch = false;
                if (hasResults) {
                    String[] keywords = query.toLowerCase().split("\\s+");
                    for (DbChunk c : results) {
                        String text = (c.getText() + " " + c.getTitle()).toLowerCase();
                        for (String kw : keywords) {
                            if (kw.length() > 3 && text.contains(kw)) {
                                keywordMatch = true;
                                break;
                            }
                        }
                        if (keywordMatch) break;
                    }
                }

                if (hasResults) {
                    passed++;
                    System.out.printf("[PASS] %3d. %-50s (%d results, %dms)%n",
                            i + 1, truncate(query, 50), results.size(), ms);
                } else {
                    failed++;
                    System.out.printf("[FAIL] %3d. %-50s (no results, %dms)%n",
                            i + 1, truncate(query, 50), ms);
                }

            } catch (Exception e) {
                failed++;
                long ms = (System.nanoTime() - t0) / 1_000_000;
                totalMs += ms;
                System.out.printf("[ERR]  %3d. %-50s (%s)%n",
                        i + 1, truncate(query, 50), e.getMessage());
            }
        }

        System.out.println("=".repeat(60));
        System.out.printf("Results: %d passed, %d failed out of %d queries%n", passed, failed, queries.size());
        System.out.printf("Total time: %dms, Avg: %dms/query%n", totalMs, queries.size() > 0 ? totalMs / queries.size() : 0);

        cross.close();

        // Exit with error code if any failed
        if (failed > 0) {
            System.exit(1);
        }
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen - 3) + "...";
    }
}
