package org.example;

import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * PopulateRealisticDB
 *
 * - Use environment variables:
 *   DB_URL (default: jdbc:postgresql://localhost:5432/learning_db)
 *   DB_USER (default: postgres)
 *   DB_PASS (no default: must set or provide 'postgres' as fallback)
 *
 * - Usage:
 *   mvn package -DskipTests
 *   set DB_URL=jdbc:postgresql://localhost:5432/learning_db
 *   set DB_USER=postgres
 *   set DB_PASS=YourPasswordHere
 *   mvn -Dexec.mainClass=org.example.PopulateRealisticDB -Dexec.args="--L 5" exec:java
 *
 * L controls how many topics/classes/assignments per course (default 5).
 */
public class PopulateRealisticDB {
    private static final DateTimeFormatter DT = DateTimeFormatter.ISO_DATE_TIME;
    private static final Random RNG = new Random(42);

    public static void main(String[] args) throws Exception {
        int L = 5;
        for (int i = 0; i < args.length; i++) {
            if ("--L".equals(args[i]) && i + 1 < args.length) {
                try { L = Integer.parseInt(args[i+1]); } catch (NumberFormatException ignored) {}
            }
        }

        String defaultUrl = "jdbc:postgresql://localhost:5432/learning_db";
        String url = System.getenv().getOrDefault("DB_URL", defaultUrl);
        String user = System.getenv().getOrDefault("DB_USER", "postgres");
        String password = System.getenv().getOrDefault("DB_PASS", "postgres");

        System.out.println("Connecting to: " + url + " as " + user);
        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            conn.setAutoCommit(false);

            createSchema(conn);
            clearTables(conn);
            populate(conn, L);

            conn.commit();
            System.out.println("\nDatabase populated successfully with L=" + L);
        } catch (SQLException ex) {
            System.err.println("Database error: " + ex.getMessage());
            ex.printStackTrace();
            throw ex;
        }
    }

    // ----- SCHEMA -----
    private static void createSchema(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("CREATE TABLE IF NOT EXISTS courses (id SERIAL PRIMARY KEY, code TEXT UNIQUE NOT NULL, title TEXT NOT NULL, description TEXT, created_at TIMESTAMP DEFAULT NOW());");

            s.execute("CREATE TABLE IF NOT EXISTS topics (id SERIAL PRIMARY KEY, course_id INTEGER NOT NULL REFERENCES courses(id) ON DELETE CASCADE, code TEXT NOT NULL, title TEXT NOT NULL, description TEXT, position INTEGER NOT NULL, created_at TIMESTAMP DEFAULT NOW(), UNIQUE(course_id, position));");

            s.execute("CREATE TABLE IF NOT EXISTS classes (id SERIAL PRIMARY KEY, topic_id INTEGER NOT NULL REFERENCES topics(id) ON DELETE CASCADE, title TEXT NOT NULL, content TEXT, class_number INTEGER NOT NULL, learned_at TIMESTAMP, created_at TIMESTAMP DEFAULT NOW(), UNIQUE(topic_id, class_number));");

            s.execute("CREATE TABLE IF NOT EXISTS assignments (id SERIAL PRIMARY KEY, title TEXT NOT NULL, description TEXT, created_at TIMESTAMP DEFAULT NOW(), due_date DATE);");

            s.execute("CREATE TABLE IF NOT EXISTS assignment_topics (assignment_id INTEGER NOT NULL REFERENCES assignments(id) ON DELETE CASCADE, topic_id INTEGER NOT NULL REFERENCES topics(id) ON DELETE CASCADE, PRIMARY KEY (assignment_id, topic_id));");

            s.execute("CREATE TABLE IF NOT EXISTS resources (id SERIAL PRIMARY KEY, class_id INTEGER NOT NULL REFERENCES classes(id) ON DELETE CASCADE, kind TEXT NOT NULL, url TEXT, description TEXT, created_at TIMESTAMP DEFAULT NOW());");
        }
    }

    // clear existing rows so re-run is idempotent
    private static void clearTables(Connection conn) throws SQLException {
        try (Statement s = conn.createStatement()) {
            // order: child tables first
            s.execute("TRUNCATE assignment_topics, resources, assignments, classes, topics, courses RESTART IDENTITY CASCADE;");
            System.out.println("Cleared existing tables (if any).");
        }
    }

    // ----- POPULATION -----
    private static void populate(Connection conn, int L) throws SQLException {
        // Realistic course list
        List<CourseDefinition> courses = realisticCourses();

        // prepared statements
        String insCourseSql = "INSERT INTO courses(code, title, description) VALUES (?, ?, ?) RETURNING id";
        String insTopicSql = "INSERT INTO topics(course_id, code, title, description, position) VALUES (?, ?, ?, ?, ?) RETURNING id";
        String insClassSql = "INSERT INTO classes(topic_id, title, content, class_number, learned_at) VALUES (?, ?, ?, ?, ?)";
        String insAssignmentSql = "INSERT INTO assignments(title, description, due_date) VALUES (?, ?, ? ) RETURNING id";
        String insAssignTopicSql = "INSERT INTO assignment_topics(assignment_id, topic_id) VALUES (?, ?)";
        String insResourceSql = "INSERT INTO resources(class_id, kind, url, description) VALUES (?, ?, ?, ?)";

        try (PreparedStatement pc = conn.prepareStatement(insCourseSql);
             PreparedStatement pt = conn.prepareStatement(insTopicSql);
             PreparedStatement pcl = conn.prepareStatement(insClassSql);
             PreparedStatement pa = conn.prepareStatement(insAssignmentSql);
             PreparedStatement pat = conn.prepareStatement(insAssignTopicSql);
             PreparedStatement pr = conn.prepareStatement(insResourceSql)) {

            for (int ci = 0; ci < courses.size(); ci++) {
                CourseDefinition cd = courses.get(ci);
                pc.setString(1, "C" + (ci+1));
                pc.setString(2, cd.title);
                pc.setString(3, cd.description);
                try (ResultSet rs = pc.executeQuery()) {
                    rs.next();
                    int courseId = rs.getInt(1);

                    // topics: prefer curated list, fill up to L with generated extras
                    List<TopicDefinition> topicDefs = cd.topics;
                    for (int ti = 0; ti < L; ti++) {
                        TopicDefinition tdef;
                        if (ti < topicDefs.size()) {
                            tdef = topicDefs.get(ti);
                        } else {
                            // generate generic topic
                            tdef = new TopicDefinition("Topic " + (ti+1), "Concepts and practice for " + cd.title.toLowerCase());
                        }
                        String topicCode = "C" + (ci+1) + "-T" + (ti+1);
                        pt.setInt(1, courseId);
                        pt.setString(2, topicCode);
                        pt.setString(3, tdef.title);
                        pt.setString(4, tdef.description);
                        pt.setInt(5, ti+1);
                        try (ResultSet trs = pt.executeQuery()) {
                            trs.next();
                            int topicId = trs.getInt(1);

                            // classes per topic: create L classes
                            for (int cl = 1; cl <= L; cl++) {
                                String classTitle = String.format("%s — Lecture %d", tdef.title, cl);
                                String content = generateClassContent(cd.title, tdef.title, cl);
                                LocalDateTime learnedAt = randomRecentDate();
                                pcl.setInt(1, topicId);
                                pcl.setString(2, classTitle);
                                pcl.setString(3, content);
                                pcl.setInt(4, cl);
                                pcl.setTimestamp(5, Timestamp.valueOf(learnedAt));
                                pcl.executeUpdate();

                                // get last inserted class id to attach resources
                                int classId;
                                try (PreparedStatement find = conn.prepareStatement("SELECT id FROM classes WHERE topic_id=? AND class_number=?")) {
                                    find.setInt(1, topicId);
                                    find.setInt(2, cl);
                                    try (ResultSet fr = find.executeQuery()) {
                                        fr.next();
                                        classId = fr.getInt(1);
                                    }
                                }

                                // attach a few resources to the class (1-3)
                                int numResources = 1 + RNG.nextInt(3);
                                for (int r = 0; r < numResources; r++) {
                                    Resource res = generateResource(cd.title, tdef.title, cl, r);
                                    pr.setInt(1, classId);
                                    pr.setString(2, res.kind);
                                    pr.setString(3, res.url);
                                    pr.setString(4, res.description);
                                    pr.executeUpdate();
                                }
                            }

                            // assignments per topic: create L assignments
                            for (int a = 1; a <= L; a++) {
                                String aTitle = String.format("Assignment %d — %s", a, tdef.title);
                                String aDesc = String.format("Complete exercises and short project for %s. Focus on practical examples and short answers.", tdef.title);
                                LocalDate due = LocalDate.now().plusDays(7 + RNG.nextInt(21));
                                pa.setString(1, aTitle);
                                pa.setString(2, aDesc);
                                pa.setDate(3, java.sql.Date.valueOf(LocalDate.now().plusDays(7 + RNG.nextInt(21))));

                                try (ResultSet ars = pa.executeQuery()) {
                                    ars.next();
                                    int assignId = ars.getInt(1);
                                    // link assignment -> this topic
                                    pat.setInt(1, assignId);
                                    pat.setInt(2, topicId);
                                    pat.executeUpdate();

                                    // occasionally cross-link assignment to another topic in same course
                                    if (RNG.nextDouble() < 0.12 && L > 1) {
                                        int otherTopicPos = 1 + RNG.nextInt(L);
                                        if (otherTopicPos != (ti+1)) {
                                            try (PreparedStatement findOther = conn.prepareStatement("SELECT id FROM topics WHERE course_id=? AND position=?")) {
                                                findOther.setInt(1, courseId);
                                                findOther.setInt(2, otherTopicPos);
                                                try (ResultSet fo = findOther.executeQuery()) {
                                                    if (fo.next()) {
                                                        int otherTopicId = fo.getInt(1);
                                                        try {
                                                            pat.setInt(1, assignId);
                                                            pat.setInt(2, otherTopicId);
                                                            pat.executeUpdate();
                                                        } catch (SQLException ignore) {}
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ---- helpers and data generation ----

    private static LocalDateTime randomRecentDate() {
        int days = RNG.nextInt(180); // within last 180 days
        int hour = 9 + RNG.nextInt(6);
        return LocalDateTime.now().minusDays(days).withHour(hour).withMinute(0).withSecond(0).withNano(0);
    }

    private static String generateClassContent(String course, String topic, int classNum) {
        return String.format("Lecture %d on %s — %s. In this session we cover fundamentals, small examples, and a hands-on exercise. Key takeaways: conceptual overview, 2-3 worked examples, suggested readings.", classNum, topic, course);
    }

    private static Resource generateResource(String course, String topic, int classNumber, int idx) {
        // cycle through some realistic resource types
        String[] kinds = {"video", "slides", "article", "repo", "notebook"};
        String kind = kinds[(topic.hashCode() + classNumber + idx) & 0x7fffffff % kinds.length];
        // Build synthetic but realistic URLs / descriptions
        String base;
        switch (kind) {
            case "video":
                base = "https://www.youtube.com/watch?v=" + randomAlphaNum(10);
                break;
            case "slides":
                base = "https://docs.example.com/" + slugify(topic) + "/slides.pdf";
                break;
            case "article":
                base = "https://medium.com/topic/" + slugify(topic) + "-guide-" + randomAlphaNum(6);
                break;
            case "repo":
                base = "https://github.com/example/" + slugify(topic) + "-examples";
                break;
            default:
                base = "https://colab.research.google.com/" + randomAlphaNum(8);
        }
        String desc = String.format("%s resource for %s — short description and why it's useful.", capitalize(kind), topic);
        return new Resource(kind, base, desc);
    }

    private static String slugify(String s) {
        return s.toLowerCase().replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
    }

    private static String randomAlphaNum(int len) {
        String chars = "abcdefghijklmnopqrstuvwxyz0123456789";
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) sb.append(chars.charAt(RNG.nextInt(chars.length())));
        return sb.toString();
    }

    private static String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // realistic course + topics definitions
    private static List<CourseDefinition> realisticCourses() {
        List<CourseDefinition> out = new ArrayList<>();

        // Machine Learning
        out.add(new CourseDefinition("Machine Learning",
                "An introduction to machine learning: supervised and unsupervised methods, basic deep learning, model evaluation, and practical workflows.",
                Arrays.asList(
                        new TopicDefinition("Supervised Learning", "Regression, classification, metrics and models."),
                        new TopicDefinition("Unsupervised Learning", "Clustering, dimensionality reduction, K-Means, PCA."),
                        new TopicDefinition("Neural Networks & Deep Learning", "Foundations of neural nets, backpropagation, basic architectures."),
                        new TopicDefinition("Model Evaluation & Validation", "Cross-validation, overfitting, metrics."),
                        new TopicDefinition("Feature Engineering & Pipelines", "Preprocessing, feature selection, pipelines.")
                )));

        // Java Programming
        out.add(new CourseDefinition("Java Programming",
                "Core Java language and ecosystem: OOP, collections, concurrency, I/O, and building small server apps.",
                Arrays.asList(
                        new TopicDefinition("Java Syntax & OOP", "Classes, inheritance, polymorphism, encapsulation."),
                        new TopicDefinition("Collections & Generics", "List, Set, Map, generics, iterators."),
                        new TopicDefinition("Concurrency & Threads", "Thread model, executors, synchronization."),
                        new TopicDefinition("I/O, NIO & Files", "Streams, buffers, file operations."),
                        new TopicDefinition("JVM Internals & Performance", "Memory model, garbage collection basics.")
                )));

        // SQL & Databases
        out.add(new CourseDefinition("SQL & Databases",
                "Relational database fundamentals: SQL queries, joins, indexes, transactions, and basic schema design.",
                Arrays.asList(
                        new TopicDefinition("Basic SQL & SELECT", "SELECT, WHERE, GROUP BY, HAVING."),
                        new TopicDefinition("Joins & Subqueries", "INNER, LEFT, RIGHT joins; correlated subqueries."),
                        new TopicDefinition("Indexes & Performance", "How indexes work, explain plans."),
                        new TopicDefinition("Transactions & Isolation", "ACID, isolation levels, locks."),
                        new TopicDefinition("Schema Design & Normalization", "Normalization, ER design.")
                )));

        // Web Development
        out.add(new CourseDefinition("Web Development",
                "Frontend + backend basics: HTML/CSS/JS, REST APIs, simple backend frameworks and deployment basics.",
                Arrays.asList(
                        new TopicDefinition("HTML & CSS Fundamentals", "Semantics, responsive layout, Flexbox."),
                        new TopicDefinition("JavaScript & DOM", "Syntax, events, DOM manipulation."),
                        new TopicDefinition("REST APIs & Backend", "Designing REST endpoints, JSON, HTTP."),
                        new TopicDefinition("Frontend Frameworks", "React/Vue basics and component model."),
                        new TopicDefinition("Deployment & Hosting", "Basic hosting, containers, static sites.")
                )));

        // Algorithms
        out.add(new CourseDefinition("Algorithms",
                "Algorithmic thinking and data structures: complexity, recursion, graphs, dynamic programming, sorting, and search.",
                Arrays.asList(
                        new TopicDefinition("Complexity & Big-O", "Time and space complexity analysis."),
                        new TopicDefinition("Sorting & Searching", "QuickSort, MergeSort, binary search."),
                        new TopicDefinition("Data Structures", "Arrays, linked lists, stacks, queues, trees."),
                        new TopicDefinition("Graphs & Traversal", "BFS, DFS, shortest paths."),
                        new TopicDefinition("Dynamic Programming", "Memoization, bottom-up DP examples.")
                )));

        return out;
    }

    // small helper classes
    private static class CourseDefinition {
        final String title;
        final String description;
        final List<TopicDefinition> topics;
        CourseDefinition(String title, String description, List<TopicDefinition> topics) {
            this.title = title;
            this.description = description;
            this.topics = topics;
        }
    }

    private static class TopicDefinition {
        final String title;
        final String description;
        TopicDefinition(String title, String description) { this.title = title; this.description = description; }
    }

    private static class Resource {
        final String kind;
        final String url;
        final String description;
        Resource(String kind, String url, String description) { this.kind = kind; this.url = url; this.description = description; }
    }
}
