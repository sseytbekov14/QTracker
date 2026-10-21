package com.kpmg.qtracker.migration;

import db.migration.V20__Notification_templates;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V19 makes the e-mail outbox (one row per dedupe key) and notification_template; V20 (a Java migration) fills it with
 * the texts of v5 and leaves a text already there. The hand-run undo script drops both tables.
 */
class V19EmailOutboxMigrationTest {

    private static final String UNDO_SCRIPT = "/db/rollback/V19__undo_email_outbox.sql";

    private String url;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:h2:mem:v19-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        connection = DriverManager.getConnection(url, "sa", "");
        // A schema of V18 (not empty, so Flyway takes it as the baseline)
        execute("CREATE TABLE controls (id BIGINT PRIMARY KEY, control_id VARCHAR(255))");
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Statement st = connection.createStatement()) {
            st.execute("SHUTDOWN");
        }
        connection.close();
    }

    @Test
    void theOutboxAndTheTextsOfV5_areThere() throws Exception {
        flyway("20").migrate();

        assertThat(single("SELECT COUNT(*) FROM notification_template")).isEqualTo("12");
        for (java.util.Map.Entry<String, String[]> text : V20__Notification_templates.TEXTS.entrySet()) {
            assertThat(single("SELECT subject FROM notification_template WHERE template_key = '" + text.getKey() + "'"))
                    .isEqualTo(text.getValue()[0]);
            assertThat(single("SELECT body FROM notification_template WHERE template_key = '" + text.getKey() + "'"))
                    .isEqualTo(text.getValue()[1]);
        }
        execute("INSERT INTO email_outbox (control_id, template_key, recipient_email, subject, body_html, dedupe_key,"
                + " status, created_at) VALUES (1, 'T3', 'a@x', 's', 'b', '1:T3:D3:a@x', 'PENDING', CURRENT_TIMESTAMP)");
        assertThat(single("SELECT importance || '/' || attempts FROM email_outbox")).isEqualTo("NORMAL/0");
        // One row per key; ON CONFLICT DO NOTHING skips a second one
        assertThatThrownBy(() -> execute("INSERT INTO email_outbox (template_key, recipient_email, subject, body_html,"
                + " dedupe_key, status, created_at) VALUES ('T3', 'a@x', 's', 'b', '1:T3:D3:a@x', 'PENDING', CURRENT_TIMESTAMP)"))
                .isInstanceOf(SQLException.class);
        execute("INSERT INTO email_outbox (template_key, recipient_email, subject, body_html, dedupe_key, status,"
                + " created_at) VALUES ('T3', 'a@x', 's', 'b', '1:T3:D3:a@x', 'PENDING', CURRENT_TIMESTAMP) ON CONFLICT DO NOTHING");
        assertThat(single("SELECT COUNT(*) FROM email_outbox")).isEqualTo("1");
    }

    @Test
    void aTextAlreadyThere_isLeftAsItIs() throws Exception {
        flyway("19").migrate();
        execute("INSERT INTO notification_template VALUES ('T3', 'Edited', 'Edited body', CURRENT_TIMESTAMP)");

        flyway("20").migrate();

        assertThat(single("SELECT subject FROM notification_template WHERE template_key = 'T3'")).isEqualTo("Edited");
        assertThat(single("SELECT COUNT(*) FROM notification_template")).isEqualTo("12");
    }

    @Test
    void theUndoScript_dropsBothTables() throws Exception {
        flyway("20").migrate();

        runUndoScript();

        assertThat(single("SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name)"
                + " IN ('email_outbox', 'notification_template')")).isEqualTo("0");
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

    private Flyway flyway(String target) {
        return Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("18")
                .target(target)
                .load();
    }
}
