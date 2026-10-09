package com.kpmg.qtracker.migration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Runs V7 through Flyway on an H2 controls table with the columns around the steps field, holding rows
 * as they occur: a filled steps text (with line breaks and Cyrillic), an empty one, NULL.
 */
class V7ControlOperatorReviewMigrationTest {

    private static final String SELECT_ALL = "SELECT id, control_id, facilitator, control_operator,"
            + " control_steps_performed, soqm_head_comments, process_owner_comments FROM controls ORDER BY id";

    private String url;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:h2:mem:v7-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        connection = DriverManager.getConnection(url, "sa", "");
        try (Statement st = connection.createStatement()) {
            st.execute("CREATE TABLE controls (id BIGINT PRIMARY KEY, control_id VARCHAR(255),"
                    + " facilitator TEXT, control_operator TEXT,"
                    + " control_steps_performed VARCHAR(2000), soqm_head_comments VARCHAR(2000),"
                    + " process_owner_comments VARCHAR(2000))");
            st.execute("INSERT INTO controls VALUES (1, 'HR-01', 'fac@x.kz', 'op@x.kz',"
                    + " 'Checked the register\r\nПроверено, расхождений нет', 'ok', NULL)");
            st.execute("INSERT INTO controls VALUES (2, 'HR-02', 'same@x.kz', 'SAME@x.kz', '', NULL, NULL)");
            st.execute("INSERT INTO controls VALUES (3, 'HR-03', NULL, NULL, NULL, NULL, 'done')");
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
    void migrate_addsAnEmptyNullableColumn_andChangesNoData() throws Exception {
        List<List<String>> before = rows(SELECT_ALL);

        MigrateResult result = flyway().migrate();

        assertThat(result.migrationsExecuted).isEqualTo(1);
        assertThat(rows(SELECT_ALL)).isEqualTo(before);
        assertThat(rows("SELECT control_operator_review FROM controls ORDER BY id"))
                .containsExactly(nullRow(), nullRow(), nullRow());
        try (Statement st = connection.createStatement();
             ResultSet rs = st.executeQuery("SELECT is_nullable, character_maximum_length FROM information_schema.columns"
                     + " WHERE LOWER(table_name) = 'controls' AND LOWER(column_name) = 'control_operator_review'")) {
            assertThat(rs.next()).isTrue();
            assertThat(rs.getString(1)).isEqualTo("YES");
            assertThat(rs.getLong(2)).isEqualTo(2000L);
        }
    }

    @Test
    void afterMigration_newRowsLeaveTheFieldNull_andItTakes2000Characters() throws Exception {
        flyway().migrate();

        try (Statement st = connection.createStatement()) {
            st.execute("INSERT INTO controls (id, control_id) VALUES (4, 'HR-04')");
            st.execute("UPDATE controls SET control_operator_review = '" + "x".repeat(2000) + "' WHERE id = 1");
        }
        assertThat(rows("SELECT control_operator_review FROM controls WHERE id = 4")).containsExactly(nullRow());
        assertThat(rows("SELECT LENGTH(control_operator_review) FROM controls WHERE id = 1"))
                .containsExactly(List.of("2000"));
    }

    private static List<String> nullRow() {
        List<String> row = new ArrayList<>();
        row.add(null);
        return row;
    }

    private List<List<String>> rows(String sql) throws SQLException {
        List<List<String>> rows = new ArrayList<>();
        try (Statement st = connection.createStatement(); ResultSet rs = st.executeQuery(sql)) {
            int columns = rs.getMetaData().getColumnCount();
            while (rs.next()) {
                List<String> row = new ArrayList<>();
                for (int i = 1; i <= columns; i++) {
                    row.add(rs.getString(i));
                }
                rows.add(row);
            }
        }
        return rows;
    }

    /** Applies only V7: V1-V4 are PostgreSQL scripts, the controls table they produce is created in setUp. */
    private Flyway flyway() {
        return Flyway.configure()
                .dataSource(url, "sa", "")
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("6")
                .target("7")
                .load();
    }
}
