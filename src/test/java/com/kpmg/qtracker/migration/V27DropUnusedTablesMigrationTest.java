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
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V27 drops the five tables nothing reads (data minimisation, 2026-11-05), with their rows, and leaves every other
 * table as it was; the hand-run undo script brings them back empty.
 */
class V27DropUnusedTablesMigrationTest {

    private static final String UNDO_SCRIPT = "/db/rollback/V27__undo_drop_unused_tables.sql";
    private static final List<String> TABLES = List.of("user_access_v8_backup", "user_admin_access_v11_backup",
            "users_role_backup", "control_notification_log", "control_related_controls");

    private String url;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:h2:mem:v27-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        connection = DriverManager.getConnection(url, "sa", "");
        // The tables as V26 has them, each with a row, and two tables that stay
        execute("CREATE TABLE controls (id BIGINT PRIMARY KEY, control_id VARCHAR(255))");
        execute("CREATE TABLE users (id BIGINT PRIMARY KEY, mail VARCHAR(255))");
        execute("INSERT INTO controls VALUES (1, 'HR-1'), (2, 'HR-2')");
        execute("INSERT INTO users VALUES (7, 'anna@example.test')");
        execute("CREATE TABLE user_access_v8_backup (user_id BIGINT PRIMARY KEY, access_level VARCHAR(20) NOT NULL,"
                + " access_scope VARCHAR(20) NOT NULL, migrated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        execute("INSERT INTO user_access_v8_backup (user_id, access_level, access_scope) VALUES (7, 'PARTICIPANT', 'KDN')");
        execute("CREATE TABLE user_admin_access_v11_backup (user_id BIGINT PRIMARY KEY, admin_access BOOLEAN NOT NULL,"
                + " access_level VARCHAR(20) NOT NULL, access_scope VARCHAR(20) NOT NULL,"
                + " migrated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        execute("INSERT INTO user_admin_access_v11_backup (user_id, admin_access, access_level, access_scope)"
                + " VALUES (7, TRUE, 'PARTICIPANT', 'ALL')");
        execute("CREATE TABLE users_role_backup (user_id BIGINT PRIMARY KEY, role VARCHAR(255), secondary_role VARCHAR(255),"
                + " migrated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP)");
        execute("INSERT INTO users_role_backup (user_id, role) VALUES (7, 'FACILITATOR')");
        execute("CREATE TABLE control_notification_log (id BIGINT PRIMARY KEY, control_id BIGINT NOT NULL,"
                + " created_at TIMESTAMP NOT NULL, notification_code VARCHAR(255) NOT NULL, scheduled_date DATE NOT NULL)");
        execute("INSERT INTO control_notification_log VALUES (1, 1, CURRENT_TIMESTAMP, 'REMINDER', CURRENT_DATE)");
        execute("CREATE TABLE control_related_controls (id BIGINT PRIMARY KEY, control_id BIGINT NOT NULL"
                + " REFERENCES controls (id) ON DELETE CASCADE, related_control_id BIGINT NOT NULL"
                + " REFERENCES controls (id) ON DELETE CASCADE, sort_order INTEGER NOT NULL)");
        execute("CREATE INDEX idx_control_related_controls_related ON control_related_controls (related_control_id)");
        execute("INSERT INTO control_related_controls VALUES (1, 1, 2, 0)");
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Statement st = connection.createStatement()) {
            st.execute("SHUTDOWN");
        }
        connection.close();
    }

    @Test
    void theFiveTablesGo_everythingElseStays() throws Exception {
        flyway().migrate();

        for (String table : TABLES) {
            assertThat(tableCount(table)).as(table).isZero();
        }
        assertThat(single("SELECT COUNT(*) FROM controls")).isEqualTo("2");
        assertThat(single("SELECT mail FROM users WHERE id = 7")).isEqualTo("anna@example.test");
    }

    @Test
    void theUndoScript_bringsTheTablesBack_empty() throws Exception {
        flyway().migrate();

        runUndoScript();

        for (String table : TABLES) {
            assertThat(tableCount(table)).as(table).isEqualTo(1);
            assertThat(single("SELECT COUNT(*) FROM " + table)).as(table).isEqualTo("0");
        }
        // The links table takes a link again, and refuses one to a control that does not exist
        execute("INSERT INTO control_related_controls (control_id, related_control_id, sort_order) VALUES (1, 2, 0)");
        assertThat(single("SELECT COUNT(*) FROM control_related_controls")).isEqualTo("1");
    }

    private int tableCount(String table) throws Exception {
        return Integer.parseInt(single("SELECT COUNT(*) FROM information_schema.tables WHERE UPPER(table_name) = '"
                + table.toUpperCase() + "'"));
    }

    private void runUndoScript() throws Exception {
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
        for (String sql : withoutComments.toString().split(";")) {
            if (!sql.isBlank()) {
                execute(sql);
            }
        }
    }

    private void execute(String sql) throws SQLException {
        try (Statement st = connection.createStatement()) {
            st.execute(sql);
        }
    }

    private String single(String sql) throws Exception {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).as(sql).isTrue();
            return rs.getString(1);
        }
    }

    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("26")
                .target("27")
                .load();
    }
}
