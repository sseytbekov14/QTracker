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
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * V17 on H2: the list Other Related Controls is chosen from, empty; a Control ID once only by its key (case and
 * spaces do not count), active by default, with the time it came; the hand-run undo script drops it.
 */
class V17RelatedControlRefMigrationTest {

    private static final String UNDO_SCRIPT = "/db/rollback/V17__undo_related_control_ref.sql";

    private String url;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:h2:mem:v17-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        connection = DriverManager.getConnection(url, "sa", "");
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE controls (id BIGINT PRIMARY KEY, control_id VARCHAR(255))");
            st.execute("INSERT INTO controls VALUES (10, 'HR-1')");
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
    void theListComes_empty_oneRowPerKey_activeByDefault() throws Exception {
        flyway().migrate();

        assertThat(single("SELECT COUNT(*) FROM related_control_ref")).isEqualTo("0");
        execute("INSERT INTO related_control_ref (control_id, control_key) VALUES ('HR-CTRL-1', 'hr-ctrl-1')");
        assertThat(single("SELECT CAST(active AS VARCHAR) || '|' || CASE WHEN created_at IS NULL THEN 'no' ELSE 'yes' END"
                + " FROM related_control_ref")).isEqualTo("TRUE|yes");
        assertThatThrownBy(() -> execute("INSERT INTO related_control_ref (control_id, control_key) VALUES "
                + "('hr-ctrl-1 ', 'hr-ctrl-1')")).isInstanceOf(SQLException.class);
        assertThat(single("SELECT COUNT(*) FROM controls")).isEqualTo("1");
    }

    @Test
    void theUndoScript_dropsTheList_andTwiceIsHarmless() throws Exception {
        flyway().migrate();
        execute("INSERT INTO related_control_ref (control_id, control_key) VALUES ('HR-CTRL-1', 'hr-ctrl-1')");

        runUndoScript();
        assertThat(single("SELECT COUNT(*) FROM information_schema.tables WHERE LOWER(table_name) = 'related_control_ref'"))
                .isEqualTo("0");
        assertThat(single("SELECT control_id FROM controls WHERE id = 10")).isEqualTo("HR-1");
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
                .baselineVersion("16")
                .target("17")
                .load();
    }
}
