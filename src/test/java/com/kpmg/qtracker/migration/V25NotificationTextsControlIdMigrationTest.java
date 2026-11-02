package com.kpmg.qtracker.migration;

import com.kpmg.qtracker.service.notification.Template;
import db.migration.V21__Notification_texts;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V25 leaves only the Control ID in the texts: the texts of V21 become those of {@link Template}, and in a text changed
 * by hand only the pair "{CONTROL_ID} {CONTROL_NAME}" goes. The hand-run undo script brings the description back.
 */
class V25NotificationTextsControlIdMigrationTest {

    private static final String UNDO_SCRIPT = "/db/rollback/V25__undo_notification_texts_control_id.sql";

    private String url;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:h2:mem:v25-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        connection = DriverManager.getConnection(url, "sa", "");
        // The texts as V21 left them (not empty, so Flyway takes the schema as the baseline)
        execute("CREATE TABLE notification_template (template_key VARCHAR(20) PRIMARY KEY, subject VARCHAR(500) NOT NULL,"
                + " body TEXT NOT NULL, updated_at TIMESTAMP)");
        for (Map.Entry<String, String[]> text : V21__Notification_texts.TEXTS.entrySet()) {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO notification_template (template_key, subject, body) VALUES (?, ?, ?)")) {
                insert.setString(1, text.getKey());
                insert.setString(2, text.getValue()[0]);
                insert.setString(3, text.getValue()[1]);
                insert.executeUpdate();
            }
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
    void theTextsBecomeThoseOfTemplate_andATextChangedByHandLosesOnlyThePair() throws Exception {
        setBody("T7", "Dear {NAME},\nOurs: {CONTROL_ID} {CONTROL_NAME} is back.\n{LINK}");
        setBody("T3", "Dear {NAME},\nAbout {CONTROL_NAME} only.\n{LINK}");

        flyway().migrate();

        for (Template template : Template.values()) {
            String expected = switch (template) {
                case T7 -> "Dear {NAME},\nOurs: {CONTROL_ID} is back.\n{LINK}";
                case T3 -> "Dear {NAME},\nAbout {CONTROL_NAME} only.\n{LINK}";
                default -> template.defaultBody();
            };
            assertThat(body(template.name())).as(template.name()).isEqualTo(expected);
            assertThat(single("SELECT subject FROM notification_template WHERE template_key = '" + template.name() + "'"))
                    .as(template.name()).isEqualTo(V21__Notification_texts.TEXTS.get(template.name())[0]);
        }
    }

    @Test
    void onlyAChangedTextGetsANewDate() throws Exception {
        setBody("T3", "Dear {NAME},\nNothing to change.\n{LINK}");

        flyway().migrate();

        assertThat(single("SELECT updated_at FROM notification_template WHERE template_key = 'T3'")).isNull();
        assertThat(single("SELECT updated_at FROM notification_template WHERE template_key = 'T8'")).isNotNull();
    }

    @Test
    void theUndoScript_bringsTheDescriptionBack() throws Exception {
        flyway().migrate();
        setBody("T3", "Dear {NAME},\nChanged after V25, no ID.\n{LINK}");

        runUndoScript();

        for (Map.Entry<String, String[]> text : V21__Notification_texts.TEXTS.entrySet()) {
            String expected = text.getKey().equals("T3") ? "Dear {NAME},\nChanged after V25, no ID.\n{LINK}"
                    : text.getValue()[1];
            assertThat(body(text.getKey())).as(text.getKey()).isEqualTo(expected);
        }
    }

    private void setBody(String key, String body) throws Exception {
        try (PreparedStatement edit = connection.prepareStatement(
                "UPDATE notification_template SET body = ? WHERE template_key = ?")) {
            edit.setString(1, body);
            edit.setString(2, key);
            edit.executeUpdate();
        }
    }

    private String body(String key) throws Exception {
        return single("SELECT body FROM notification_template WHERE template_key = '" + key + "'");
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
                .baselineVersion("24")
                .target("25")
                .load();
    }
}
