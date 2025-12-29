package org.example;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Map;
import java.util.Optional;

import org.junit.AfterClass;
import static org.junit.Assert.assertTrue;
import org.junit.BeforeClass;
import org.junit.Test;
import org.testcontainers.containers.GenericContainer;

public class SqlServiceIntegrationTest {

    // Use GenericContainer to avoid compile-time dependency on JdbcDatabaseContainer
    public static GenericContainer<?> pg = new GenericContainer<>("postgres:15")
            .withEnv("POSTGRES_DB", "learning_db")
            .withEnv("POSTGRES_USER", "postgres")
            .withEnv("POSTGRES_PASSWORD", "postgres")
            .withExposedPorts(5432);

    private static String getJdbcUrl() {
        return "jdbc:postgresql://" + pg.getHost() + ":" + pg.getMappedPort(5432) + "/learning_db";
    }

    private static String getUsername() {
        return "postgres";
    }

    private static String getPassword() {
        return "postgres";
    }

    @BeforeClass
    public static void beforeAll() throws Exception {
        // Skip test if Docker is not available (useful for local dev on machines without Docker)
        if (!org.testcontainers.DockerClientFactory.instance().isDockerAvailable()) {
            org.junit.Assume.assumeTrue("Docker not available, skipping", false);
        }
        pg.start();
        try (Connection c = java.sql.DriverManager.getConnection(getJdbcUrl(), getUsername(), getPassword())) {
            try (Statement s = c.createStatement()) {
                s.execute("CREATE TABLE IF NOT EXISTS courses (id SERIAL PRIMARY KEY, code VARCHAR(20) UNIQUE NOT NULL, title VARCHAR(200) NOT NULL);");
                s.execute("CREATE TABLE IF NOT EXISTS topics (id SERIAL PRIMARY KEY, course_id INT REFERENCES courses(id), code VARCHAR(20) UNIQUE NOT NULL, title VARCHAR(200) NOT NULL);");
                s.execute("CREATE TABLE IF NOT EXISTS classes (id SERIAL PRIMARY KEY, topic_id INT REFERENCES topics(id), title TEXT, learned_at TIMESTAMP);");

                s.execute("INSERT INTO courses(code,title) VALUES('C2','Java Programming') ON CONFLICT DO NOTHING;");
                s.execute("INSERT INTO topics(course_id,code,title) VALUES( (SELECT id FROM courses WHERE code='C2'),'C2-T3','Concurrency & Threads') ON CONFLICT DO NOTHING;");
                s.execute("INSERT INTO classes(topic_id,title,learned_at) VALUES( (SELECT id FROM topics WHERE code='C2-T3'), 'Concurrency class', '2025-06-21' )");
            }
        }
    }

    @AfterClass
    public static void afterAll() {
        pg.stop();
    }

    @Test
    public void testQueryLearnedAtRange() throws Exception {
        String url = getJdbcUrl();
        String user = getUsername();
        String pass = getPassword();

        SqlService sql = new SqlService(url, user, pass);
        Optional<Map<String,String>> r = sql.queryLearnedAtRange("C2-T3");
        assertTrue("Expected a date range result", r.isPresent());
        Map<String,String> m = r.get();
        assertTrue("Expected earliest or latest keys", m.containsKey("earliest") || m.containsKey("latest"));
    }
}
