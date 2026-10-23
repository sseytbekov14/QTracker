package com.kpmg.qtracker.migration;

import com.kpmg.qtracker.service.notification.Template;
import db.migration.V20__Notification_templates;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * V21 replaces the texts of v5 (V20) with the texts of 2026-10-20 ({@link Template}), but only where the text is still
 * that of V20: a text changed by hand stays. The hand-run undo script brings the texts of V20 back where V21's are
 * still there.
 */
class V21NotificationTextsMigrationTest {

    private static final String UNDO_SCRIPT = "/db/rollback/V21__undo_notification_texts.sql";

    private String url;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:h2:mem:v21-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
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
    void theNewTexts_replaceTheTextsOfV20_butNotATextChangedByHand() throws Exception {
        flyway("20").migrate();
        try (PreparedStatement edit = connection.prepareStatement(
                "UPDATE notification_template SET body = ? WHERE template_key = 'T7'")) {
            edit.setString(1, "Dear Facilitator,\nEdited by SoQM.\n{LINK}");
            edit.executeUpdate();
        }

        flyway("21").migrate();

        for (Template template : Template.values()) {
            if (template == Template.T7) {
                assertThat(body("T7")).isEqualTo("Dear Facilitator,\nEdited by SoQM.\n{LINK}");
                continue;
            }
            assertThat(single("SELECT subject FROM notification_template WHERE template_key = '" + template.name() + "'"))
                    .as(template.name()).isEqualTo(template.defaultSubject());
            assertThat(body(template.name())).as(template.name()).isEqualTo(template.defaultBody());
        }
        assertThat(single("SELECT COUNT(*) FROM notification_template")).isEqualTo("12");
    }

    @Test
    void theUndoScript_bringsTheTextsOfV20Back_whereV21sAreStillThere() throws Exception {
        flyway("21").migrate();
        try (PreparedStatement edit = connection.prepareStatement(
                "UPDATE notification_template SET body = ? WHERE template_key = 'T3'")) {
            edit.setString(1, "Dear {NAME},\nChanged after V21.\n{LINK}");
            edit.executeUpdate();
        }

        runUndoScript();

        for (Template template : Template.values()) {
            String expected = template == Template.T3 ? "Dear {NAME},\nChanged after V21.\n{LINK}"
                    : V20__Notification_templates.TEXTS.get(template.name())[1];
            assertThat(body(template.name())).as(template.name()).isEqualTo(expected);
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
