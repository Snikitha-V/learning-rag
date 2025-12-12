package org.example;

import java.sql.*;
import java.util.*;

/**
 * SqlService executes parameterized queries for common factual intents
 * and builds a tiny DbChunk containing the SQL result so it can be injected
 * into RAG context.
 */
public class SqlService {
    private final String url;
    private final String user;
    private final String pass;

    public SqlService(String url, String user, String pass) {
        this.url = url;
        this.user = user;
        this.pass = pass;
    }

    private Connection getConn() throws SQLException {
        return DriverManager.getConnection(url, user, pass);
    }

    /**
     * Helper to resolve topic code (e.g., "C1-T1") to topic_id.
     * Returns empty if not found.
     */
    private Optional<Integer> resolveTopicId(Connection c, String topicCode) throws SQLException {
        String sql = "SELECT id FROM topics WHERE UPPER(code) = UPPER(?)";
        try (PreparedStatement p = c.prepareStatement(sql)) {
            p.setString(1, topicCode.trim());
            try (ResultSet rs = p.executeQuery()) {
                if (rs.next()) {
                    return Optional.of(rs.getInt("id"));
                }
                return Optional.empty();
            }
        }
    }

    /**
     * Example: handle "when did I learn <topic_code>?" or "when did I learn C2-T3?"
     * Returns a small map with keys "earliest" and "latest" as ISO timestamps, or empty if none.
     * topicCode must be exact e.g., "C2-T3" (caller must extract / validate).
     */
    public Optional<Map<String, String>> queryLearnedAtRange(String topicCode) throws SQLException {
        try (Connection c = getConn()) {
            Optional<Integer> topicIdOpt = resolveTopicId(c, topicCode);
            if (topicIdOpt.isEmpty()) return Optional.empty();
            
            String sql = "SELECT MIN(learned_at) AS earliest, MAX(learned_at) AS latest FROM classes WHERE topic_id = ?;";
            try (PreparedStatement p = c.prepareStatement(sql)) {
                p.setInt(1, topicIdOpt.get());
                try (ResultSet rs = p.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    Timestamp e = rs.getTimestamp("earliest");
                    Timestamp l = rs.getTimestamp("latest");
                    if (e == null && l == null) return Optional.empty();
                    Map<String,String> out = new LinkedHashMap<>();
                    if (e != null) out.put("earliest", e.toLocalDateTime().toString());
                    if (l != null) out.put("latest", l.toLocalDateTime().toString());
                    return Optional.of(out);
                }
            }
        }
    }

    /**
     * Example: count classes for a topic
     */
    public Optional<Integer> queryCountClassesForTopic(String topicCode) throws SQLException {
        try (Connection c = getConn()) {
            Optional<Integer> topicIdOpt = resolveTopicId(c, topicCode);
            if (topicIdOpt.isEmpty()) return Optional.empty();
            
            String sql = "SELECT COUNT(*) AS cnt FROM classes WHERE topic_id = ?;";
            try (PreparedStatement p = c.prepareStatement(sql)) {
                p.setInt(1, topicIdOpt.get());
                try (ResultSet rs = p.executeQuery()) {
                    if (!rs.next()) return Optional.of(0);
                    return Optional.of(rs.getInt("cnt"));
                }
            }
        }
    }

    /**
     * Example: list assignments for a class (returns up to limit rows)
     */
    public List<Map<String,String>> queryAssignmentsForClass(int classId, int limit) throws SQLException {
        String sql = "SELECT a.id AS assignment_id, a.title, a.due_date " +
                     "FROM assignments a " +
                     "JOIN assignment_topics at ON a.id = at.assignment_id " +
                     "JOIN classes c ON at.topic_id = c.topic_id " +
                     "WHERE c.id = ? ORDER BY a.due_date LIMIT ?;";
        try (Connection c = getConn(); PreparedStatement p = c.prepareStatement(sql)) {
            p.setInt(1, classId);
            p.setInt(2, limit);
            try (ResultSet rs = p.executeQuery()) {
                List<Map<String,String>> res = new ArrayList<>();
                while (rs.next()) {
                    Map<String,String> r = new LinkedHashMap<>();
                    r.put("assignment_id", String.valueOf(rs.getInt("assignment_id")));
                    r.put("title", rs.getString("title"));
                    java.sql.Date d = rs.getDate("due_date");
                    r.put("due_date", d == null ? "" : d.toString());
                    res.add(r);
                }
                return res;
            }
        }
    }

    /**
     * Build a synthetic DbChunk representing SQL results.
     * The chunk id is prefixed with SQL- for easy detection.
     */
    public DbChunk buildSqlChunk(String idSuffix, String title, String body) {
        DbChunk c = new DbChunk();
        c.setChunkId("SQL-" + idSuffix);
        c.setChunkType("sql_result");
        c.setTitle(title);
        c.setText(body);
        return c;
    }

    // Helper to make a readable body for date range
    public String sqlDateRangeBody(String topicCode, Map<String,String> range) {
        StringBuilder sb = new StringBuilder();
        sb.append("SQL_RESULT for topic=").append(topicCode).append("\n");
        range.forEach((k,v)-> sb.append(k).append(": ").append(v).append("\n"));
        return sb.toString();
    }

    // Helper to make count body
    public String sqlCountBody(String topicCode, int cnt) {
        return String.format("SQL_RESULT for topic=%s\nTotal classes: %d\n", topicCode, cnt);
    }

    /**
     * List all courses from the database.
     */
    public List<Map<String,String>> listCourses() throws SQLException {
        String sql = "SELECT code, title FROM courses ORDER BY code;";
        try (Connection c = getConn(); PreparedStatement p = c.prepareStatement(sql)) {
            try (ResultSet rs = p.executeQuery()) {
                List<Map<String,String>> res = new ArrayList<>();
                while (rs.next()) {
                    Map<String,String> r = new LinkedHashMap<>();
                    r.put("code", rs.getString("code"));
                    r.put("title", rs.getString("title"));
                    res.add(r);
                }
                return res;
            }
        }
    }

    /**
     * List all topics from the database.
     */
    public List<Map<String,String>> listTopics() throws SQLException {
        String sql = "SELECT code, title FROM topics ORDER BY code;";
        try (Connection c = getConn(); PreparedStatement p = c.prepareStatement(sql)) {
            try (ResultSet rs = p.executeQuery()) {
                List<Map<String,String>> res = new ArrayList<>();
                while (rs.next()) {
                    Map<String,String> r = new LinkedHashMap<>();
                    r.put("code", rs.getString("code"));
                    r.put("title", rs.getString("title"));
                    res.add(r);
                }
                return res;
            }
        }
    }

    /**
     * Topics (and their classes) taught on a given date (ISO yyyy-MM-dd).
     */
    public List<Map<String,String>> queryTopicsOnDate(String isoDate) throws SQLException {
        String sql = "SELECT c.id AS class_id, c.learned_at, t.code, t.title " +
                     "FROM classes c JOIN topics t ON c.topic_id = t.id " +
                     "WHERE DATE(c.learned_at) = ? ORDER BY t.code;";
        try (Connection conn = getConn(); PreparedStatement p = conn.prepareStatement(sql)) {
            p.setDate(1, java.sql.Date.valueOf(isoDate));
            try (ResultSet rs = p.executeQuery()) {
                List<Map<String,String>> res = new ArrayList<>();
                while (rs.next()) {
                    Map<String,String> r = new LinkedHashMap<>();
                    r.put("class_id", String.valueOf(rs.getInt("class_id")));
                    Timestamp ts = rs.getTimestamp("learned_at");
                    r.put("learned_at", ts == null ? "" : ts.toLocalDateTime().toString());
                    r.put("topic_code", rs.getString("code"));
                    r.put("topic_title", rs.getString("title"));
                    res.add(r);
                }
                return res;
            }
        }
    }

    /**
     * List classes taught by a given instructor name (case-insensitive match).
     */
    public List<Map<String,String>> queryClassesByInstructor(String instructorName) throws SQLException {
        String sql = "SELECT c.id AS class_id, c.learned_at, t.code AS topic_code, t.title AS topic_title " +
                     "FROM classes c " +
                     "JOIN instructors i ON c.instructor_id = i.id " +
                     "JOIN topics t ON c.topic_id = t.id " +
                     "WHERE UPPER(i.name) = UPPER(?) ORDER BY c.learned_at;";
        try (Connection conn = getConn(); PreparedStatement p = conn.prepareStatement(sql)) {
            p.setString(1, instructorName.trim());
            try (ResultSet rs = p.executeQuery()) {
                List<Map<String,String>> res = new ArrayList<>();
                while (rs.next()) {
                    Map<String,String> r = new LinkedHashMap<>();
                    r.put("class_id", String.valueOf(rs.getInt("class_id")));
                    Timestamp ts = rs.getTimestamp("learned_at");
                    r.put("learned_at", ts == null ? "" : ts.toLocalDateTime().toString());
                    r.put("topic_code", rs.getString("topic_code"));
                    r.put("topic_title", rs.getString("topic_title"));
                    res.add(r);
                }
                return res;
            }
        }
    }

    /**
     * Topics with the most assignments (limit rows).
     */
    public List<Map<String,String>> queryTopicsWithMostAssignments(int limit) throws SQLException {
        String sql = "SELECT t.code, t.title, COUNT(at.assignment_id) AS cnt " +
                     "FROM topics t JOIN assignment_topics at ON at.topic_id = t.id " +
                     "GROUP BY t.id ORDER BY cnt DESC LIMIT ?;";
        try (Connection conn = getConn(); PreparedStatement p = conn.prepareStatement(sql)) {
            p.setInt(1, limit);
            try (ResultSet rs = p.executeQuery()) {
                List<Map<String,String>> res = new ArrayList<>();
                while (rs.next()) {
                    Map<String,String> r = new LinkedHashMap<>();
                    r.put("code", rs.getString("code"));
                    r.put("title", rs.getString("title"));
                    r.put("assignments_count", String.valueOf(rs.getInt("cnt")));
                    res.add(r);
                }
                return res;
            }
        }
    }

    /**
     * List all instructors.
     */
    public List<Map<String,String>> listInstructors() throws SQLException {
        String sql = "SELECT id, name FROM instructors ORDER BY name;";
        try (Connection conn = getConn(); PreparedStatement p = conn.prepareStatement(sql)) {
            try (ResultSet rs = p.executeQuery()) {
                List<Map<String,String>> res = new ArrayList<>();
                while (rs.next()) {
                    Map<String,String> r = new LinkedHashMap<>();
                    r.put("id", String.valueOf(rs.getInt("id")));
                    r.put("name", rs.getString("name"));
                    res.add(r);
                }
                return res;
            }
        }
    }

    /**
     * List classes on a given date.
     */
    public List<Map<String,String>> listClassesOnDate(String isoDate) throws SQLException {
        String sql = "SELECT c.id AS class_id, c.learned_at, t.code AS topic_code, t.title AS topic_title " +
                     "FROM classes c JOIN topics t ON c.topic_id = t.id " +
                     "WHERE DATE(c.learned_at) = ? ORDER BY c.learned_at;";
        try (Connection conn = getConn(); PreparedStatement p = conn.prepareStatement(sql)) {
            p.setDate(1, java.sql.Date.valueOf(isoDate));
            try (ResultSet rs = p.executeQuery()) {
                List<Map<String,String>> res = new ArrayList<>();
                while (rs.next()) {
                    Map<String,String> r = new LinkedHashMap<>();
                    r.put("class_id", String.valueOf(rs.getInt("class_id")));
                    Timestamp ts = rs.getTimestamp("learned_at");
                    r.put("learned_at", ts == null ? "" : ts.toLocalDateTime().toString());
                    r.put("topic_code", rs.getString("topic_code"));
                    r.put("topic_title", rs.getString("topic_title"));
                    res.add(r);
                }
                return res;
            }
        }
    }

    /**
     * Assignments due on a specific date.
     */
    public List<Map<String,String>> assignmentsDueOnDate(String isoDate) throws SQLException {
        String sql = "SELECT id AS assignment_id, title, due_date FROM assignments WHERE due_date = ? ORDER BY due_date;";
        try (Connection conn = getConn(); PreparedStatement p = conn.prepareStatement(sql)) {
            p.setDate(1, java.sql.Date.valueOf(isoDate));
            try (ResultSet rs = p.executeQuery()) {
                List<Map<String,String>> res = new ArrayList<>();
                while (rs.next()) {
                    Map<String,String> r = new LinkedHashMap<>();
                    r.put("assignment_id", String.valueOf(rs.getInt("assignment_id")));
                    r.put("title", rs.getString("title"));
                    java.sql.Date d = rs.getDate("due_date");
                    r.put("due_date", d == null ? "" : d.toString());
                    res.add(r);
                }
                return res;
            }
        }
    }

    /**
     * Topics that have never been taught (no classes).
     */
    public List<Map<String,String>> topicsNeverTaught() throws SQLException {
        String sql = "SELECT t.code, t.title FROM topics t LEFT JOIN classes c ON c.topic_id = t.id WHERE c.id IS NULL ORDER BY t.code;";
        try (Connection conn = getConn(); PreparedStatement p = conn.prepareStatement(sql)) {
            try (ResultSet rs = p.executeQuery()) {
                List<Map<String,String>> res = new ArrayList<>();
                while (rs.next()) {
                    Map<String,String> r = new LinkedHashMap<>();
                    r.put("code", rs.getString("code"));
                    r.put("title", rs.getString("title"));
                    res.add(r);
                }
                return res;
            }
        }
    }

    /**
     * Classes that have no assignments (based on assignment_topics mapping by topic).
     */
    public List<Map<String,String>> classesWithNoAssignments() throws SQLException {
        String sql = "SELECT c.id AS class_id, t.code AS topic_code, t.title AS topic_title " +
                     "FROM classes c JOIN topics t ON c.topic_id = t.id " +
                     "LEFT JOIN assignment_topics at ON at.topic_id = c.topic_id " +
                     "WHERE at.assignment_id IS NULL ORDER BY c.id;";
        try (Connection conn = getConn(); PreparedStatement p = conn.prepareStatement(sql)) {
            try (ResultSet rs = p.executeQuery()) {
                List<Map<String,String>> res = new ArrayList<>();
                while (rs.next()) {
                    Map<String,String> r = new LinkedHashMap<>();
                    r.put("class_id", String.valueOf(rs.getInt("class_id")));
                    r.put("topic_code", rs.getString("topic_code"));
                    r.put("topic_title", rs.getString("topic_title"));
                    res.add(r);
                }
                return res;
            }
        }
    }

    /**
     * Number of assignments per topic (including zero).
     */
    public List<Map<String,String>> countAssignmentsPerTopic() throws SQLException {
        String sql = "SELECT t.code, t.title, COUNT(at.assignment_id) AS cnt " +
                     "FROM topics t LEFT JOIN assignment_topics at ON at.topic_id = t.id " +
                     "GROUP BY t.id ORDER BY cnt DESC;";
        try (Connection conn = getConn(); PreparedStatement p = conn.prepareStatement(sql)) {
            try (ResultSet rs = p.executeQuery()) {
                List<Map<String,String>> res = new ArrayList<>();
                while (rs.next()) {
                    Map<String,String> r = new LinkedHashMap<>();
                    r.put("code", rs.getString("code"));
                    r.put("title", rs.getString("title"));
                    r.put("assignments_count", String.valueOf(rs.getInt("cnt")));
                    res.add(r);
                }
                return res;
            }
        }
    }

    /**
     * First and last class timestamps for a given topic code.
     */
    public Optional<Map<String,String>> queryFirstLastClassForTopic(String topicCode) throws SQLException {
        try (Connection c = getConn()) {
            Optional<Integer> topicIdOpt = resolveTopicId(c, topicCode);
            if (topicIdOpt.isEmpty()) return Optional.empty();
            String sql = "SELECT MIN(learned_at) AS first_at, MAX(learned_at) AS last_at FROM classes WHERE topic_id = ?;";
            try (PreparedStatement p = c.prepareStatement(sql)) {
                p.setInt(1, topicIdOpt.get());
                try (ResultSet rs = p.executeQuery()) {
                    if (!rs.next()) return Optional.empty();
                    Timestamp f = rs.getTimestamp("first_at");
                    Timestamp l = rs.getTimestamp("last_at");
                    if (f == null && l == null) return Optional.empty();
                    Map<String,String> out = new LinkedHashMap<>();
                    if (f != null) out.put("first", f.toLocalDateTime().toString());
                    if (l != null) out.put("last", l.toLocalDateTime().toString());
                    return Optional.of(out);
                }
            }
        }
    }
}
