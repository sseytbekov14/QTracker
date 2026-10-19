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
 * V18 on H2 tables shaped like V16: Other Related Controls come back as a TEXT column holding the Control IDs each
 * control linked (control_related_controls), in their order; IT Applications become TEXT with their values as they
 * were. The hand-run undo script drops the column (the links stay) and cuts IT Applications back to 255 characters.
 */
class V18RelatedFieldsTextMigrationTest {

    private static final String UNDO_SCRIPT = "/db/rollback/V18__undo_related_fields_text.sql";

    private String url;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:h2:mem:v18-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        connection = DriverManager.getConnection(url, "sa", "");
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE controls (id BIGINT PRIMARY KEY, control_id VARCHAR(255), it_applications VARCHAR(255))");
            st.execute("CREATE TABLE control_related_controls (id BIGINT PRIMARY KEY, control_id BIGINT NOT NULL,"
                    + " related_control_id BIGINT NOT NULL, sort_order INTEGER NOT NULL)");
            st.execute("INSERT INTO controls VALUES (10, 'HR-1/FY27', 'KICS'), (11, 'EP-2/FY27', NULL),"
                    + " (12, ' KDN;3 ', 'Cloud Environment / Secure Server OS'), (13, 'HR-4', NULL)");
            // 10 links 12 then 11 (sort order), 13 links 10
            st.execute("INSERT INTO control_related_controls VALUES (1, 10, 11, 1), (2, 10, 12, 0), (3, 13, 10, 0)");
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
    void theLinksBecomeControlIds_inTheirOrder_itApplicationsStay() throws Exception {
        flyway().migrate();

        assertThat(single("SELECT other_related_controls FROM controls WHERE id = 10")).isEqualTo("KDN,3; EP-2/FY27");
        assertThat(single("SELECT other_related_controls FROM controls WHERE id = 13")).isEqualTo("HR-1/FY27");
        assertThat(single("SELECT COALESCE(other_related_controls, '-') FROM controls WHERE id = 11")).isEqualTo("-");
        assertThat(single("SELECT it_applications FROM controls WHERE id = 12")).isEqualTo("Cloud Environment / Secure Server OS");
        assertThat(single("SELECT COUNT(*) FROM control_related_controls")).isEqualTo("3");

        // Several values longer than 255 characters fit now
        String many = "x".repeat(200) + "; " + "y".repeat(200);
        execute("UPDATE controls SET it_applications = '" + many + "', other_related_controls = '" + many + "' WHERE id = 11");
        assertThat(single("SELECT it_applications FROM controls WHERE id = 11")).isEqualTo(many);
    }

    @Test
    void theUndoScript_dropsTheColumn_keepsTheLinks_cutsItApplications() throws Exception {
        flyway().migrate();
        execute("UPDATE controls SET it_applications = '" + "z".repeat(300) + "' WHERE id = 11");

        runUndoScript();
        assertThat(single("SELECT COUNT(*) FROM information_schema.columns WHERE LOWER(table_name) = 'controls'"
                + " AND LOWER(column_name) = 'other_related_controls'")).isEqualTo("0");
        assertThat(single("SELECT LENGTH(it_applications) FROM controls WHERE id = 11")).isEqualTo("255");
        assertThat(single("SELECT it_applications FROM controls WHERE id = 10")).isEqualTo("KICS");
        assertThat(single("SELECT COUNT(*) FROM control_related_controls")).isEqualTo("3");
        runUndoScript();
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
                .baselineVersion("17")
                .target("18")
                .load();
    }
}
