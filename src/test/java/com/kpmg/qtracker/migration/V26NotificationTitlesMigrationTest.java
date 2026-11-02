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
 * V26 gives T3, T4 and T5 the names of {@link Template}, unless a name was changed by hand; the hand-run undo script
 * brings the old names back where V26's are still there.
 */
class V26NotificationTitlesMigrationTest {

    private static final String UNDO_SCRIPT = "/db/rollback/V26__undo_notification_titles.sql";

    private String url;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:h2:mem:v26-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        connection = DriverManager.getConnection(url, "sa", "");
        // The names as V21 left them (not empty, so Flyway takes the schema as the baseline)
        execute("CREATE TABLE notification_template (template_key VARCHAR(10) PRIMARY KEY, subject VARCHAR(500) NOT NULL,"
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
    void theNamesBecomeThoseOfTemplate_butNotANameChangedByHand() throws Exception {
        setSubject("T5", "Our own name");

        flyway().migrate();

        for (Template template : Template.values()) {
            assertThat(subject(template.name())).as(template.name())
                    .isEqualTo(template == Template.T5 ? "Our own name" : template.defaultSubject());
        }
    }

    @Test
    void theUndoScript_bringsTheOldNamesBack_whereV26sAreStillThere() throws Exception {
        flyway().migrate();
        setSubject("T3", "Changed after V26");

        runUndoScript();

        assertThat(subject("T3")).isEqualTo("Changed after V26");
        assertThat(subject("T4")).isEqualTo("Control Deadline Today");
        assertThat(subject("T5")).isEqualTo("Control Deadline Notification");
    }

    private void setSubject(String key, String subject) throws Exception {
        try (PreparedStatement edit = connection.prepareStatement(
                "UPDATE notification_template SET subject = ? WHERE template_key = ?")) {
            edit.setString(1, subject);
            edit.setString(2, key);
            edit.executeUpdate();
        }
    }

    private String subject(String key) throws Exception {
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT subject FROM notification_template WHERE template_key = '" + key + "'")) {
            assertThat(rs.next()).as(key).isTrue();
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
                .baselineVersion("25")
                .target("26")
                .load();
    }
}
