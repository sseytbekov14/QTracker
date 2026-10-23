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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V13 on H2 tables shaped like V1: the column soqm_development_materials goes, the rest of the control (its
 * Documents attachments too) and the audit log naming the field stay; the hand-run undo script brings back an
 * empty nullable column, without the values.
 */
class V13DevelopmentMaterialsMigrationTest {

    private static final String UNDO_SCRIPT = "/db/rollback/V13__undo_drop_soqm_development_materials.sql";

    private String url;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:h2:mem:v13-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        connection = DriverManager.getConnection(url, "sa", "");
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE controls (id BIGINT PRIMARY KEY, control_id VARCHAR(255),"
                    + " attachment_documents_path TEXT, soqm_development_materials VARCHAR(255))");
            st.execute("CREATE TABLE audit_log (id BIGINT PRIMARY KEY, control_id BIGINT, new_values TEXT)");
            st.execute("INSERT INTO controls VALUES (10, 'HR-1', 'plan.docx', 'Available'), (11, 'HR-2', NULL, NULL)");
            st.execute("INSERT INTO audit_log VALUES (1, 10, '{\"SoQM Development Materials\":\"Available\"}')");
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
    void theColumnGoes_theControlsAndTheirHistoryStay() throws Exception {
        flyway().migrate();

        assertThat(materialsColumns()).isZero();
        assertThat(single("SELECT COUNT(*) FROM controls")).isEqualTo("2");
        assertThat(single("SELECT attachment_documents_path FROM controls WHERE id = 10")).isEqualTo("plan.docx");
        assertThat(single("SELECT new_values FROM audit_log WHERE id = 1"))
                .isEqualTo("{\"SoQM Development Materials\":\"Available\"}");

        // Twice is harmless (IF EXISTS), e.g. a database where the column was already dropped by hand
        try (Statement st = connection.createStatement()) {
            st.execute("ALTER TABLE controls DROP COLUMN IF EXISTS soqm_development_materials");
        }
    }

    @Test
    void theUndoScript_bringsBackAnEmptyNullableColumn() throws Exception {
        flyway().migrate();
        runUndoScript();

        assertThat(materialsColumns()).isEqualTo(1);
        assertThat(single("SELECT COUNT(*) FROM controls WHERE soqm_development_materials IS NOT NULL")).isEqualTo("0");
        assertThat(single("SELECT is_nullable FROM information_schema.columns WHERE LOWER(table_name) = 'controls'"
                + " AND LOWER(column_name) = 'soqm_development_materials'")).isEqualTo("YES");
        try (Statement st = connection.createStatement()) {
            st.execute("INSERT INTO controls (id, control_id) VALUES (12, 'HR-3')");
        }
        // Run again: nothing changes
        runUndoScript();
        assertThat(materialsColumns()).isEqualTo(1);
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
        try (Statement st = connection.createStatement()) {
            for (String sql : withoutComments.toString().split(";")) {
                if (!sql.isBlank()) {
                    st.execute(sql);
                }
            }
        }
    }

    private int materialsColumns() throws Exception {
        return Integer.parseInt(single("SELECT COUNT(*) FROM information_schema.columns WHERE"
                + " LOWER(table_name) = 'controls' AND LOWER(column_name) = 'soqm_development_materials'"));
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
                .baselineVersion("12")
                .target("13")
                .load();
    }
}
