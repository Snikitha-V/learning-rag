package org.example;

import java.util.*;

/**
 * MMRTest - Tests MMR (Maximal Marginal Relevance) reranking.
 * 
 * Usage:
 *   mvn -q -Dexec.mainClass="org.example.MMRTest" exec:java
 */
public class MMRTest {

    public static void main(String[] args) throws Exception {
        System.out.println("Loading embedder from: " + Config.EMBED_MODEL_PATH);
        try (OnnxEmbedder embedder = new OnnxEmbedder(
                Config.EMBED_MODEL_PATH + "/model.onnx",
                Config.EMBED_MODEL_PATH,
                384
        )) {
            // Sanity check embedding
            OnnxEmbedder.sanityCheckEmbed(embedder);

            // Create query vector
            String query = "binary search algorithm implementation";
            float[] qvec = embedder.embed(query);
            System.out.println("Query: " + query);

            // Get candidates from Qdrant
            QdrantClient qdrant = new QdrantClient(Config.QDRANT_URL, Config.QDRANT_COLLECTION);
            List<Candidate> dense = qdrant.search(qvec, 50, Config.QDRANT_EF);
            System.out.println("Dense candidates: " + dense.size());

            // Test MMR
            testMMR(dense, qvec);
        }
    }

    public static void testMMR(List<Candidate> merged, float[] qvec) {
        System.out.println("\n--- MMR Reranking Test ---");
        List<Candidate> out = MMR.rerank(merged, qvec, 20, 0.7);
        System.out.println("MMR selected: " + out.size() + " (from " + merged.size() + " candidates)");
        System.out.println("\nTop MMR results:");
        int i = 1;
        for (Candidate c : out) {
            String chunkId = c.payload != null && c.payload.get("chunk_id") != null 
                    ? c.payload.get("chunk_id").toString() 
                    : c.id;
            System.out.printf("%2d. %s (dense_score=%.4f)%n", i++, chunkId, c.score);
        }
    }
}
