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
 * V28 makes the Completed notification (T12) say "Control {CONTROL_ID} has been successfully completed." (2026-11-06),
 * also in a text changed by hand that still has the sentence, and touches no other text; the hand-run undo script takes
 * the word out again.
 */
public class V28NotificationCompletedSuccessfullyMigrationTest {

    /** T12 as V25 left it, before V28. */
    public static final String BEFORE_V28 = Template.T12.defaultBody()
            .replace("has been successfully completed.", "has been completed.");

    private static final String UNDO_SCRIPT = "/db/rollback/V28__undo_notification_completed_successfully.sql";

    private String url;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:h2:mem:v28-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        connection = DriverManager.getConnection(url, "sa", "");
        // The texts as V25 left them: V21's, the control named by its ID alone
        execute("CREATE TABLE notification_template (template_key VARCHAR(10) PRIMARY KEY, subject VARCHAR(500) NOT NULL,"
                + " body TEXT NOT NULL, updated_at TIMESTAMP)");
        for (Map.Entry<String, String[]> text : V21__Notification_texts.TEXTS.entrySet()) {
            try (PreparedStatement insert = connection.prepareStatement(
                    "INSERT INTO notification_template (template_key, subject, body) VALUES (?, ?, ?)")) {
                insert.setString(1, text.getKey());
                insert.setString(2, text.getValue()[0]);
                insert.setString(3, text.getValue()[1].replace("{CONTROL_ID} {CONTROL_NAME}", "{CONTROL_ID}"));
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
    void t12SaysSuccessfully_andNoOtherTextChanges() throws Exception {
        assertThat(body("T12")).isEqualTo(BEFORE_V28);
        Map<String, String> before = new java.util.HashMap<>();
        for (Template template : Template.values()) {
            before.put(template.name(), body(template.name()));
        }

        flyway().migrate();

        assertThat(body("T12")).isEqualTo(Template.T12.defaultBody())
                .contains("Control {CONTROL_ID} has been successfully completed.");
        for (Template template : Template.values()) {
            if (template != Template.T12) {
                assertThat(body(template.name())).as(template.name()).isEqualTo(before.get(template.name()));
            }
        }
        assertThat(single("SELECT COUNT(*) FROM notification_template WHERE updated_at IS NOT NULL")).isEqualTo("1");
    }

    @Test
    void aT12ChangedByHand_keepsItsOwnWords() throws Exception {
        setBody("T12", "Dear {NAME},\nGood news: Control {CONTROL_ID} has been completed. Thank you!\n{LINK}");

        flyway().migrate();

        assertThat(body("T12"))
                .isEqualTo("Dear {NAME},\nGood news: Control {CONTROL_ID} has been successfully completed. Thank you!\n{LINK}");
    }

    @Test
    void aT12WithoutTheSentence_staysAsItIs() throws Exception {
        setBody("T12", "Dear {NAME},\nDone: {CONTROL_ID}.\n{LINK}");

        flyway().migrate();

        assertThat(body("T12")).isEqualTo("Dear {NAME},\nDone: {CONTROL_ID}.\n{LINK}");
        assertThat(single("SELECT COUNT(*) FROM notification_template WHERE updated_at IS NOT NULL")).isEqualTo("0");
    }

    @Test
    void theUndoScript_takesTheWordOutAgain() throws Exception {
        flyway().migrate();

        runUndoScript();

        assertThat(body("T12")).isEqualTo(BEFORE_V28);
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

    private String single(String sql) throws Exception {
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            assertThat(rs.next()).as(sql).isTrue();
            return rs.getString(1);
        }
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

    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("27")
                .target("28")
                .load();
    }
}
