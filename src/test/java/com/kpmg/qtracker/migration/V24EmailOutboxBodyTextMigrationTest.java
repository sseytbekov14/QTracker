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
 * V24 adds the plain-text part of an e-mail to the outbox, empty for the e-mails already there. The hand-run undo
 * script drops the column and keeps the rows.
 */
class V24EmailOutboxBodyTextMigrationTest {

    private static final String UNDO_SCRIPT = "/db/rollback/V24__undo_email_outbox_body_text.sql";
    private static final String COLUMN = "SELECT COUNT(*) FROM information_schema.columns"
            + " WHERE UPPER(table_name) = 'EMAIL_OUTBOX' AND UPPER(column_name) = 'BODY_TEXT'";

    private String url;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:h2:mem:v24-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        connection = DriverManager.getConnection(url, "sa", "");
        // The outbox of V23, as far as the column needs it, with one e-mail queued before V24
        execute("CREATE TABLE email_outbox (id BIGINT PRIMARY KEY, body_html TEXT NOT NULL)");
        execute("INSERT INTO email_outbox VALUES (1, '<div>Dear Anna,</div>')");
    }

    @AfterEach
    void tearDown() throws Exception {
        try (Statement st = connection.createStatement()) {
            st.execute("SHUTDOWN");
        }
        connection.close();
    }

    @Test
    void theColumnIsThere_emptyForAnEmailQueuedBefore() throws Exception {
        flyway().migrate();

        assertThat(single(COLUMN)).isEqualTo("1");
        assertThat(single("SELECT COALESCE(body_text, 'null') || ' ' || body_html FROM email_outbox WHERE id = 1"))
                .isEqualTo("null <div>Dear Anna,</div>");
    }

    @Test
    void theUndoScript_dropsTheColumn_keepsTheRows() throws Exception {
        flyway().migrate();
        execute("UPDATE email_outbox SET body_text = 'Dear Anna,' WHERE id = 1");

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

        assertThat(single(COLUMN)).isEqualTo("0");
        assertThat(single("SELECT body_html FROM email_outbox WHERE id = 1")).isEqualTo("<div>Dear Anna,</div>");
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
                .baselineVersion("23")
                .target("24")
                .load();
    }
}
