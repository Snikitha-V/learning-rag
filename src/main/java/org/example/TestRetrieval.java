package org.example;

import java.util.List;

public class TestRetrieval {
    public static void main(String[] args) throws Exception {
        System.out.println("Loading embedder from: " + Config.EMBED_MODEL_PATH);
        OnnxEmbedder embedder = new OnnxEmbedder(
                Config.EMBED_MODEL_PATH + "/model.onnx",
                Config.EMBED_MODEL_PATH,
                384  // max sequence length (tokens), not embedding dim
        );

        // Sanity check: verify embedding dimension and L2 normalization
        OnnxEmbedder.sanityCheckEmbed(embedder);

        QdrantClient qdrant = new QdrantClient(Config.QDRANT_URL, Config.QDRANT_COLLECTION);
        LuceneIndexService lucene = new LuceneIndexService(Config.LUCENE_INDEX_DIR);
        CrossEncoderScorer cross = new CrossEncoderScorer(embedder);
        DataFetcher db = new DataFetcher();

        RetrievalService svc = new RetrievalService(embedder, qdrant, lucene, cross, db);

        String query = "How do I implement binary search in Java?";
        if (args.length > 0) query = String.join(" ", args);

        System.out.println("Query: " + query);
        List<DbChunk> ctx = svc.retrieve(query);

        System.out.println("Retrieved context chunks: " + ctx.size());
        int i = 1;
        for (DbChunk c : ctx) {
            System.out.println("---- CONTEXT " + i++ + " ----");
            System.out.println("ID: " + c.getChunkId());
            System.out.println("Title: " + c.getTitle());
            String t = c.getText();
            if (t != null) {
                System.out.println(t.length() > 800 ? t.substring(0,800) + "..." : t);
            } else {
                System.out.println("[no text]");
            }
        }

        // Assembled context with head+tail trimming
        System.out.println("\n==== ASSEMBLED CONTEXT (charBudget=2000) ====");
        String assembled = RetrievalService.assembleContext(ctx, Config.CONTEXT_K, 2000);
        System.out.println(assembled);

        cross.close();
    }
}
