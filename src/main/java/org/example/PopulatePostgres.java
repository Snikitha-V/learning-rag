package org.example;

import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.Random;

public class PopulatePostgres {

    static final DateTimeFormatter DT = DateTimeFormatter.ISO_DATE_TIME;

    public static void main(String[] args) {
        int L = 5; // default
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--L") && i+1 < args.length) {
                L = Integer.parseInt(args[i+1]);
            }
        }

        // PostgreSQL connection
        String url = "jdbc:postgresql://localhost:5432/learning_db";
        String user = "postgres";        // your username
        String password = "Snikitha05!"; // <<< replace this

        try (Connection conn = DriverManager.getConnection(url, user, password)) {
            conn.setAutoCommit(false);

            createSchema(conn);
            populateData(conn, L);

            conn.commit();
            System.out.println("\nDatabase populated successfully with L=" + L);
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void createSchema(Connection conn) throws SQLException {
    try (Statement s = conn.createStatement()) {

        s.execute("""
            CREATE TABLE IF NOT EXISTS courses (
                id SERIAL PRIMARY KEY,
                code TEXT UNIQUE NOT NULL,
                title TEXT NOT NULL,
                description TEXT,
                created_at TIMESTAMP DEFAULT NOW()
            );
        """);

        s.execute("""
            CREATE TABLE IF NOT EXISTS topics (
                id SERIAL PRIMARY KEY,
                course_id INTEGER NOT NULL REFERENCES courses(id) ON DELETE CASCADE,
                code TEXT NOT NULL,
                title TEXT NOT NULL,
                description TEXT,
                position INTEGER NOT NULL,
                created_at TIMESTAMP DEFAULT NOW(),
                UNIQUE(course_id, position)
            );
        """);

        s.execute("""
            CREATE TABLE IF NOT EXISTS classes (
                id SERIAL PRIMARY KEY,
                topic_id INTEGER NOT NULL REFERENCES topics(id) ON DELETE CASCADE,
                title TEXT NOT NULL,
                content TEXT,
                class_number INTEGER NOT NULL,
                learned_at TIMESTAMP,
                created_at TIMESTAMP DEFAULT NOW(),
                UNIQUE(topic_id, class_number)
            );
        """);

        s.execute("""
            CREATE TABLE IF NOT EXISTS assignments (
                id SERIAL PRIMARY KEY,
                title TEXT NOT NULL,
                description TEXT,
                created_at TIMESTAMP DEFAULT NOW(),
                due_date DATE
            );
        """);

        s.execute("""
            CREATE TABLE IF NOT EXISTS assignment_topics (
                assignment_id INTEGER NOT NULL REFERENCES assignments(id) ON DELETE CASCADE,
                topic_id INTEGER NOT NULL REFERENCES topics(id) ON DELETE CASCADE,
                PRIMARY KEY (assignment_id, topic_id)
            );
        """);

        s.execute("""
            CREATE TABLE IF NOT EXISTS resources (
                id SERIAL PRIMARY KEY,
                kind TEXT NOT NULL,
                ref_id INTEGER NOT NULL,
                url TEXT,
                description TEXT
            );
        """);

    }
}


    private static void populateData(Connection conn, int L) throws SQLException {
        Random rnd = new Random(50);

        String insCourse = "INSERT INTO courses(code, title, description) VALUES (?,?,?) RETURNING id";
        String insTopic = "INSERT INTO topics(course_id, code, title, description, position) VALUES (?,?,?,?,?) RETURNING id";
        String insClass = "INSERT INTO classes(topic_id, title, content, class_number, learned_at) VALUES (?,?,?,?,?)";
        String insAssignment = "INSERT INTO assignments(title, description, due_date) VALUES (?,?,?) RETURNING id";
        String insAssignTopic = "INSERT INTO assignment_topics(assignment_id, topic_id) VALUES (?,?)";

        for (int ci = 1; ci <= L; ci++) {

            int courseId;
            try (PreparedStatement pc = conn.prepareStatement(insCourse)) {
                pc.setString(1, "C" + ci);
                pc.setString(2, "Course " + ci);
                pc.setString(3, "Description for Course " + ci);

                ResultSet rs = pc.executeQuery();
                rs.next();
                courseId = rs.getInt(1);
            }

            // L topics per course
            for (int ti = 1; ti <= L; ti++) {
                int topicId;
                try (PreparedStatement pt = conn.prepareStatement(insTopic)) {
                    String code = "C" + ci + "-T" + ti;
                    pt.setInt(1, courseId);
                    pt.setString(2, code);
                    pt.setString(3, "Topic " + ti + " (Course " + ci + ")");
                    pt.setString(4, "Autogenerated topic");
                    pt.setInt(5, ti);

                    ResultSet rs = pt.executeQuery();
                    rs.next();
                    topicId = rs.getInt(1);
                }

                // L classes per topic
                for (int cl = 1; cl <= L; cl++) {
                    try (PreparedStatement pcl = conn.prepareStatement(insClass)) {
                        pcl.setInt(1, topicId);
                        pcl.setString(2, "Class " + cl + " of Topic C" + ci + "-T" + ti);
                        pcl.setString(3, "Dummy content");
                        pcl.setInt(4, cl);

                        LocalDateTime learned = LocalDateTime.now().minusDays(rnd.nextInt(80));
                        pcl.setTimestamp(5, Timestamp.valueOf(learned));

                        pcl.executeUpdate();
                    }
                }

                // L assignments per topic
                for (int a = 1; a <= L; a++) {
                    int assignmentId;
                    try (PreparedStatement pa = conn.prepareStatement(insAssignment)) {
                        pa.setString(1, "Assignment " + a + " for C" + ci + "-T" + ti);
                        pa.setString(2, "Assignment description");
                        pa.setDate(3, Date.valueOf(LocalDate.now().plusDays(10 + rnd.nextInt(20))));

                        ResultSet rs = pa.executeQuery();
                        rs.next();
                        assignmentId = rs.getInt(1);
                    }

                    try (PreparedStatement pat = conn.prepareStatement(insAssignTopic)) {
                        pat.setInt(1, assignmentId);
                        pat.setInt(2, topicId);
                        pat.executeUpdate();
                    }
                }
            }
        }
    }
}
