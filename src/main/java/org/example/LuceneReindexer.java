package org.example;

import java.sql.*;
import java.util.*;

/**
 * LuceneReindexer - Rebuilds the Lucene BM25 index from the chunks table in PostgreSQL.
 * 
 * Usage:
 *   set DB_PASS=YourPassword
 *   mvn -q -Dexec.mainClass="org.example.LuceneReindexer" exec:java
 */
public class LuceneReindexer {

    public static void main(String[] args) throws Exception {
        String dbUrl = System.getenv().getOrDefault("DB_URL", "jdbc:postgresql://localhost:5432/learning_db");
        String dbUser = System.getenv().getOrDefault("DB_USER", "postgres");
        String dbPass = System.getenv().getOrDefault("DB_PASS", "postgres");

        System.out.println("Loading chunks from database...");
        List<DbChunk> chunks = loadAllChunks(dbUrl, dbUser, dbPass);
        System.out.println("Loaded " + chunks.size() + " chunks.");

        System.out.println("Rebuilding Lucene index at: " + Config.LUCENE_INDEX_DIR);
        LuceneIndexService lucene = new LuceneIndexService(Config.LUCENE_INDEX_DIR);
        lucene.rebuildIndex(chunks);
        System.out.println("Lucene index rebuilt successfully.");

        // Quick sanity check
        List<String> testHits = lucene.search("binary search Java", 10);
        System.out.println("Sanity check - BM25 hits for 'binary search Java': " + testHits.size());
        for (String id : testHits) {
            System.out.println("  - " + id);
        }
    }

    private static List<DbChunk> loadAllChunks(String dbUrl, String dbUser, String dbPass) throws SQLException {
        List<DbChunk> chunks = new ArrayList<>();
        String sql = "SELECT chunk_id, title, text, chunk_type, metadata::text FROM chunks";
        
        try (Connection conn = DriverManager.getConnection(dbUrl, dbUser, dbPass);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            
            while (rs.next()) {
                DbChunk c = new DbChunk();
                c.setChunkId(rs.getString("chunk_id"));
                c.setTitle(rs.getString("title"));
                c.setText(rs.getString("text"));
                c.setChunkType(rs.getString("chunk_type"));
                c.setMetadata(rs.getString("metadata"));
                chunks.add(c);
            }
        }
        return chunks;
    }
}
