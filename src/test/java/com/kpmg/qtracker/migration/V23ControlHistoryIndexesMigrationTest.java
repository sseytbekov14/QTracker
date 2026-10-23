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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V23 indexes workflow_history, workflow_steps and admin_audit_log by control; an index made by hand before the
 * release (CREATE INDEX CONCURRENTLY, same name) is left as it is. The hand-run undo script drops the three.
 */
class V23ControlHistoryIndexesMigrationTest {

    private static final String UNDO_SCRIPT = "/db/rollback/V23__undo_control_history_indexes.sql";
    private static final String INDEXES = "('IDX_WORKFLOW_HISTORY_CONTROL', 'IDX_WORKFLOW_STEPS_CONTROL',"
            + " 'IDX_ADMIN_AUDIT_LOG_CONTROL')";

    private String url;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:h2:mem:v23-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        connection = DriverManager.getConnection(url, "sa", "");
        // The three tables of V22, as far as the indexes need them
        execute("CREATE TABLE workflow_history (id BIGINT PRIMARY KEY, control_id BIGINT, created_at TIMESTAMP)");
        execute("CREATE TABLE workflow_steps (id BIGINT PRIMARY KEY, control_id BIGINT, sequence_order INT)");
        execute("CREATE TABLE admin_audit_log (id BIGINT PRIMARY KEY, control_id BIGINT, created_at TIMESTAMP)");
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Statement st = connection.createStatement()) {
            st.execute("SHUTDOWN");
        }
        connection.close();
    }

    @Test
    void theThreeIndexes_areThere() throws Exception {
        flyway().migrate();

        assertThat(single("SELECT COUNT(*) FROM information_schema.indexes WHERE UPPER(index_name) IN " + INDEXES))
                .isEqualTo("3");
    }

    @Test
    void anIndexMadeByHandBefore_isKept() throws Exception {
        execute("CREATE INDEX idx_workflow_history_control ON workflow_history (control_id, created_at)");

        flyway().migrate();

        assertThat(single("SELECT COUNT(*) FROM information_schema.indexes WHERE UPPER(index_name) IN " + INDEXES))
                .isEqualTo("3");
    }

    @Test
    void theUndoScript_dropsTheIndexes() throws Exception {
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
        for (String sql : withoutComments.toString().split(";")) {
            if (!sql.isBlank()) {
                execute(sql);
            }
        }

        assertThat(single("SELECT COUNT(*) FROM information_schema.indexes WHERE UPPER(index_name) IN " + INDEXES))
                .isEqualTo("0");
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
                .baselineVersion("22")
                .target("23")
                .load();
    }
}
