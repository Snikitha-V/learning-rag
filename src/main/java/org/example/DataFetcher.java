package org.example;

import java.sql.*;
import java.util.*;

public class DataFetcher {
    private final String jdbcUrl = "jdbc:postgresql://localhost:5432/learning_db";
    private final String dbUser = "postgres";
    private final String dbPass = "Snikitha05!";

    public DataFetcher() {
        // nothing
    }

    public Map<String, DbChunk> fetchChunks(List<String> chunkIds) throws SQLException {
        Map<String, DbChunk> out = new HashMap<>();
        if (chunkIds == null || chunkIds.isEmpty()) return out;

        // Build SQL with IN (...) safely using PreparedStatement
        StringBuilder sb = new StringBuilder();
        sb.append("SELECT chunk_id, title, text, chunk_type, metadata FROM chunks WHERE chunk_id = ANY(?)");
        try (Connection conn = DriverManager.getConnection(jdbcUrl, dbUser, dbPass)) {
            Array arr = conn.createArrayOf("text", chunkIds.toArray(new String[0]));
            try (PreparedStatement ps = conn.prepareStatement(sb.toString())) {
                ps.setArray(1, arr);
                try (ResultSet rs = ps.executeQuery()) {
                    while (rs.next()) {
                        DbChunk c = new DbChunk();
                        c.setChunkId(rs.getString("chunk_id"));
                        c.setTitle(rs.getString("title"));
                        c.setText(rs.getString("text"));
                        c.setChunkType(rs.getString("chunk_type"));
                        try {
                            c.setMetadata(rs.getString("metadata"));
                        } catch (Exception ex) {
                            c.setMetadata(null);
                        }
                        out.put(c.getChunkId(), c);
                    }
                }
            }
        }
        return out;
    }
}
