package com.kpmg.qtracker.migration;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/** V31 makes the ten free-text columns of controls TEXT, keeping every value; 20 000 characters then fit. */
class V31ControlTextFieldsMigrationTest {

    private static final List<String> COLUMNS = List.of("control_description", "prp", "process_name",
            "references_to_control", "department", "process_activities", "control_operator_review",
            "control_steps_performed", "soqm_head_comments", "process_owner_comments");

    private String url;
    private Connection connection;

    @BeforeEach
    void setUp() throws Exception {
        url = "jdbc:h2:mem:v31-" + UUID.randomUUID() + ";MODE=PostgreSQL;DB_CLOSE_DELAY=-1";
        connection = DriverManager.getConnection(url, "sa", "");
        execute("CREATE TABLE controls (id BIGINT PRIMARY KEY, control_description VARCHAR(2000), prp VARCHAR(2000),"
                + " process_name VARCHAR(255), references_to_control VARCHAR(255), department VARCHAR(255),"
                + " process_activities VARCHAR(2000), control_operator_review VARCHAR(2000),"
                + " control_steps_performed VARCHAR(2000), soqm_head_comments VARCHAR(2000), process_owner_comments VARCHAR(2000))");
        execute("INSERT INTO controls VALUES (1, 'Описание', 'PRP', 'Process', 'Refs', 'Dept', 'Activities', 'Program',"
                + " '" + "s".repeat(2000) + "', 'SoQM', 'PO')");
    }

    @AfterEach
    void tearDown() throws Exception {
        execute("SHUTDOWN");
        connection.close();
    }

    @Test
    void theColumns_areText_theValuesKept_andTwentyThousandFit() throws Exception {
        Flyway.configure().dataSource(url, "sa", "").locations("classpath:db/migration")
                .baselineOnMigrate(true).baselineVersion("30").target("31").load().migrate();

        for (String column : COLUMNS) {
            // TEXT: no size of its own (H2 shows it as CHARACTER VARYING without a length or with its maximum)
            String size = single("SELECT COALESCE(character_maximum_length, 0) FROM information_schema.columns"
                    + " WHERE LOWER(table_name) = 'controls' AND LOWER(column_name) = '" + column + "'");
            assertThat(Long.parseLong(size)).as(column).satisfiesAnyOf(n -> assertThat(n).isZero(), n -> assertThat(n).isGreaterThan(20000L));
        }
        assertThat(single("SELECT control_description FROM controls WHERE id = 1")).isEqualTo("Описание");
        assertThat(single("SELECT LENGTH(control_steps_performed) FROM controls WHERE id = 1")).isEqualTo("2000");
        execute("UPDATE controls SET control_description = '" + "d".repeat(20000) + "', process_name = '"
                + "p".repeat(20000) + "' WHERE id = 1");
        assertThat(single("SELECT LENGTH(control_description) + LENGTH(process_name) FROM controls")).isEqualTo("40000");
    }

    private void execute(String sql) throws Exception {
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
}
