package com.kpmg.qtracker.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V12 on H2 tables shaped like V1: the creator of each control is kept as text (filled from users), the table of
 * deleted users exists; the hand-run undo script takes both away.
 */
class V12UserDeletionMigrationTest {

    private static final String UNDO_SCRIPT = "/db/rollback/V12__undo_user_deletion.sql";

    private String url;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:h2:mem:v12-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        connection = DriverManager.getConnection(url, "sa", "");
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE users (id BIGINT PRIMARY KEY, mail VARCHAR(255) NOT NULL UNIQUE,"
                    + " displayname VARCHAR(255))");
            st.execute("CREATE TABLE controls (id BIGINT PRIMARY KEY, control_id VARCHAR(255), created_by BIGINT"
                    + " REFERENCES users (id))");
            st.execute("INSERT INTO users VALUES (1, 'creator@x.kz', 'Creator One'), (2, 'other@x.kz', NULL)");
            st.execute("INSERT INTO controls VALUES (10, 'HR-1', 1), (11, 'HR-2', 2), (12, 'HR-3', NULL)");
        }
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Statement st = connection.createStatement()) {
            st.execute("SHUTDOWN");
        }
        connection.close();
    }

    @Test
    void theCreatorIsKeptAsText_andDeletedUsersHasItsTable() throws Exception {
        flyway().migrate();

        assertThat(creators()).containsExactly(
                Map.entry("HR-1", "creator@x.kz|Creator One"),
                Map.entry("HR-2", "other@x.kz|null"),
                Map.entry("HR-3", "null|null"));
        try (Statement st = connection.createStatement()) {
            st.execute("INSERT INTO deleted_users (user_id, mail, display_name, role, deleted_at, deleted_by_email)"
                    + " VALUES (1, 'creator@x.kz', 'Creator One', 'User', CURRENT_TIMESTAMP, 'admin@x.kz')");
            // The deletion: the link goes, the text stays
            st.execute("UPDATE controls SET created_by = NULL WHERE created_by = 1");
            st.execute("DELETE FROM users WHERE id = 1");
        }
        assertThat(creators()).containsEntry("HR-1", "creator@x.kz|Creator One");
    }

    @Test
    void theUndoScript_takesBothAway() throws Exception {
        flyway().migrate();
        String script;
        try (InputStream in = getClass().getResourceAsStream(UNDO_SCRIPT)) {
            assertThat(in).as(UNDO_SCRIPT).isNotNull();
            script = new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
        StringBuilder withoutComments = new StringBuilder();
        for (String line : script.split("\n")) {
            if (!line.trim().startsWith("--")) {
                withoutComments.append(line).append('\n');
            }
        }
        try (Statement st = connection.createStatement()) {
            for (String sql : withoutComments.toString().split(";")) {
                if (!sql.isBlank()) {
                    st.execute(sql);
                }
            }
        }
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.columns WHERE"
                     + " LOWER(table_name) = 'controls' AND LOWER(column_name) LIKE 'created_by_%'")) {
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT COUNT(*) FROM information_schema.tables"
                     + " WHERE LOWER(table_name) = 'deleted_users'")) {
            rs.next();
            assertThat(rs.getInt(1)).isZero();
        }
    }

    private Map<String, String> creators() throws Exception {
        Map<String, String> result = new LinkedHashMap<>();
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT control_id, created_by_email, created_by_name FROM controls ORDER BY id")) {
            while (rs.next()) {
                result.put(rs.getString(1), rs.getString(2) + "|" + rs.getString(3));
            }
        }
        return result;
    }

    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("11")
                .target("12")
                .load();
    }
}
