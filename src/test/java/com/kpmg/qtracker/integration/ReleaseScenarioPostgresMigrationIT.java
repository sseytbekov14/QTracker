package com.kpmg.qtracker.integration;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Release test pass, stage I: the Flyway migrations on a real PostgreSQL - from an empty schema to the latest, and
 * from data of an earlier version (synthetic rows written at V4) to the latest, then the rollback scripts of V11 and
 * V8. Runs only with -Drelease.db.url=jdbc:postgresql://.../qtracker_release_test (and .user, .password): every test
 * empties the public schema of that temporary database (V1 looks its constraints up by table name, so the migrations
 * need a database of their own) and leaves it empty for the next run.
 */
class ReleaseScenarioPostgresMigrationIT {

    private String url;
    private String user;
    private String password;
    private String schema;

    @BeforeEach
    void database() throws Exception {
        url = System.getProperty("release.db.url", "");
        assumeTrue(url.startsWith("jdbc:postgresql:"), "PostgreSQL only: -Drelease.db.url=jdbc:postgresql://...");
        user = System.getProperty("release.db.user", "postgres");
        password = System.getProperty("release.db.password", "");
        // Never anything but the temporary database of the release test
        assertThat(url).endsWith("/qtracker_release_test");
        schema = "public";
        emptySchema();
    }

    @AfterEach
    void dropSchema() throws Exception {
        if (url.endsWith("/qtracker_release_test")) {
            emptySchema();
        }
    }

    private void emptySchema() throws Exception {
        try (Connection connection = DriverManager.getConnection(url, user, password);
             Statement statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS public CASCADE");
            statement.execute("CREATE SCHEMA public");
        }
    }

    @Test
    void anEmptyDatabase_migratesToTheLatestVersion_withTheSeedPeopleOfV2() throws Exception {
        Flyway flyway = flyway(null);
        flyway.migrate();
        MigrationInfo current = flyway.info().current();
        assertThat(current.getVersion().getVersion()).isEqualTo("11");
        for (MigrationInfo info : flyway.info().applied()) {
            assertThat(info.getState().isFailed()).as(info.getScript()).isFalse();
        }
        // V2 writes the made-up dev people into every new database; after V6 and V11 two of them are SoQM Team
        Map<String, String> seeds = rows("SELECT mail, access_level || '/' || access_scope || '/' || admin_access "
                + "FROM users WHERE mail LIKE '%@qtracker.local' ORDER BY mail");
        assertThat(seeds).containsEntry("soqm1@qtracker.local", "SOQM/ALL/true")
                .containsEntry("soqm2@qtracker.local", "SOQM/ALL/true")
                .containsEntry("admin@qtracker.local", "PARTICIPANT/ALL/false")
                .hasSize(9);
        assertThat(single("SELECT COUNT(*) FROM users WHERE password IS NOT NULL AND mail LIKE '%@qtracker.local'"))
                .isEqualTo("9");
    }

    @Test
    void dataOfAnEarlierVersion_migrates_andTheRollbackScriptsOfV11AndV8_restoreIt() throws Exception {
        flyway("4").migrate();
        execute("DELETE FROM users");
        // People with the old roles and admin flags, controls with attachment lists, as the application wrote them
        execute("INSERT INTO users (mail, displayname, role, enabled, admin_access) VALUES "
                + "('old-soqm@release.test', 'Old Soqm', 'SOQM_TEAM', TRUE, FALSE), "
                + "('old-admin@release.test', 'Old Admin', 'ADMIN', TRUE, TRUE), "
                + "('old-fac@release.test', 'Old Fac', 'FACILITATOR', TRUE, FALSE), "
                + "('old-po@release.test', 'Old Po', 'Process Owner', TRUE, TRUE), "
                + "('old-kdn@release.test', 'Old Kdn', 'KDN', TRUE, FALSE), "
                + "('old-viewer@release.test', 'Old Viewer', 'VIEWER', FALSE, FALSE)");
        execute("INSERT INTO controls (control_id, control_frequency, performance_status, control_status, "
                + "attachment_details_path, attachment_documents_path, control_operation_date, control_operation_deadline, "
                + "control_description, soqm_head_comments) VALUES "
                + "('HR-OLD-1/FY26/Central/OCT', 'Monthly', 'COMPLETED', 'ACTIVE', 'a.pdf;b.xlsx', 'c.docx', "
                + "DATE '2025-10-06', DATE '2025-10-13', 'Описание ✅', 'SoQM'), "
                + "('KDN-OLD-2', 'Quarterly', 'REVIEW', 'ACTIVE', NULL, NULL, DATE '2026-01-05', DATE '2026-01-19', NULL, NULL), "
                + "('EP-OLD-3', 'Annual', 'DRAFT', NULL, '', NULL, NULL, NULL, NULL, NULL)");

        Flyway latest = flyway(null);
        latest.migrate();
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("11");

        Map<String, String> access = rows("SELECT mail, access_level || '/' || access_scope || '/' || admin_access "
                + "FROM users WHERE mail LIKE 'old-%' ORDER BY mail");
        assertThat(access).containsEntry("old-soqm@release.test", "SOQM/ALL/true")
                .containsEntry("old-admin@release.test", "PARTICIPANT/ALL/false")
                .containsEntry("old-fac@release.test", "PARTICIPANT/OWN/false")
                .containsEntry("old-po@release.test", "PARTICIPANT/OWN/false")
                .containsEntry("old-kdn@release.test", "READ_ONLY/KDN/false")
                .containsEntry("old-viewer@release.test", "READ_ONLY/OWN/false");
        // Controls kept as they were; V5 listed their attachments, V7 and V10 added empty columns
        assertThat(single("SELECT COUNT(*) FROM controls WHERE control_id LIKE '%OLD%'")).isEqualTo("3");
        assertThat(single("SELECT control_description FROM controls WHERE control_id = 'HR-OLD-1/FY26/Central/OCT'"))
                .isEqualTo("Описание ✅");
        assertThat(single("SELECT COUNT(*) FROM control_attachments a JOIN controls c ON c.id = a.control_id "
                + "WHERE c.control_id = 'HR-OLD-1/FY26/Central/OCT'")).isEqualTo("3");
        assertThat(single("SELECT COUNT(*) FROM controls WHERE control_id LIKE '%OLD%' AND reopened_at IS NULL "
                + "AND control_operator_review IS NULL")).isEqualTo("3");
        assertThat(single("SELECT control_operation_deadline FROM controls WHERE control_id = 'KDN-OLD-2'"))
                .isEqualTo("2026-01-19");

        // Rollback of V11, then of V8 (the order the scripts are meant for)
        script("db/rollback/V11__undo_admin_access_follows_role.sql");
        assertThat(rows("SELECT mail, CAST(admin_access AS VARCHAR) FROM users WHERE mail IN "
                + "('old-soqm@release.test', 'old-admin@release.test', 'old-po@release.test') ORDER BY mail"))
                .containsEntry("old-soqm@release.test", "false")
                .containsEntry("old-admin@release.test", "true")
                .containsEntry("old-po@release.test", "true");
        script("db/rollback/V8__undo_KDN_users_read_only.sql");
        assertThat(single("SELECT access_level || '/' || access_scope FROM users WHERE mail = 'old-kdn@release.test'"))
                .isEqualTo("PARTICIPANT/KDN");
        assertThat(single("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '" + schema
                + "' AND table_name IN ('user_admin_access_v11_backup', 'user_access_v8_backup')")).isEqualTo("0");
    }

    // ---- helpers ----

    private Flyway flyway(String target) {
        var configuration = Flyway.configure()
                .dataSource(url, user, password)
                .schemas(schema)
                .createSchemas(true)
                .locations("classpath:db/migration")
                .baselineOnMigrate(true)
                .baselineVersion("0");
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private Connection connection() throws Exception {
        Connection connection = DriverManager.getConnection(url, user, password);
        try (Statement statement = connection.createStatement()) {
            statement.execute("SET search_path TO " + schema);
        }
        return connection;
    }

    private void execute(String sql) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private void script(String path) throws Exception {
        String sql = new String(new ClassPathResource(path).getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        execute(sql);
    }

    private String single(String sql) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).as(sql).isTrue();
            return rows.getString(1);
        }
    }

    private Map<String, String> rows(String sql) throws Exception {
        Map<String, String> out = new LinkedHashMap<>();
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                out.put(rows.getString(1), rows.getString(2));
            }
        }
        return out;
    }
}
