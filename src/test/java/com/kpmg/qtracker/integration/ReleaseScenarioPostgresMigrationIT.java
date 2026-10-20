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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * Release test pass, stage I: the Flyway migrations on a real PostgreSQL - from an empty schema to the latest, from
 * data of an earlier version (synthetic rows written at V4) to the latest, then the rollback scripts of V19, V18, V17, V16, V15, V14, V13, V12, V11
 * and V8, and from data of the version before (V12, SoQM Development Materials filled) to V13 and back. Runs only with -Drelease.db.url=jdbc:postgresql://.../qtracker_release_test (and .user, .password): every test
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

    private static final String LATEST = "20";

    @Test
    void anEmptyDatabase_migratesToTheLatestVersion_withTheSeedPeopleOfV2() throws Exception {
        Flyway flyway = flyway(null);
        flyway.migrate();
        MigrationInfo current = flyway.info().current();
        assertThat(current.getVersion().getVersion()).isEqualTo(LATEST);
        assertThat(materialsColumns()).isEqualTo("0");
        assertThat(single("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '" + schema
                + "' AND table_name = 'deleted_users'")).isEqualTo("1");
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
    void dataOfAnEarlierVersion_migrates_andTheRollbackScriptsOfV13ToV8_restoreIt() throws Exception {
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
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo(LATEST);

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

        assertThat(materialsColumns()).isEqualTo("0");
        assertThat(single("SELECT COUNT(*) FROM controls WHERE control_id LIKE '%OLD%' AND created_from_control_id IS NULL"))
                .isEqualTo("3");

        // V18 brought other_related_controls back as TEXT: no links, so empty
        assertThat(relatedTextColumns()).isEqualTo("1");
        assertThat(single("SELECT COUNT(*) FROM controls WHERE other_related_controls IS NOT NULL")).isEqualTo("0");
        assertThat(relatedTables()).isEqualTo("1");
        assertThat(single("SELECT COUNT(*) FROM control_related_controls")).isEqualTo("0");
        assertThat(refTables()).isEqualTo("1");

        // V19 and V20: the e-mail outbox, empty, and the twelve texts of v5
        assertThat(single("SELECT COUNT(*) FROM email_outbox")).isEqualTo("0");
        assertThat(single("SELECT COUNT(*) FROM notification_template")).isEqualTo("12");

        // Rollback of V19, V18, V17, V16, V15, V14, V13, V12, V11, then of V8 (the order the scripts are meant for)
        script("db/rollback/V19__undo_email_outbox.sql");
        assertThat(single("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '" + schema
                + "' AND table_name IN ('email_outbox', 'notification_template')")).isEqualTo("0");
        script("db/rollback/V18__undo_related_fields_text.sql");
        assertThat(relatedTextColumns()).isEqualTo("0");
        script("db/rollback/V17__undo_related_control_ref.sql");
        assertThat(refTables()).isEqualTo("0");
        script("db/rollback/V16__undo_drop_other_related_controls_text.sql");
        assertThat(relatedTextColumns()).isEqualTo("1");
        script("db/rollback/V15__undo_control_related_controls.sql");
        assertThat(relatedTables()).isEqualTo("0");
        script("db/rollback/V14__undo_control_created_from.sql");
        assertThat(createdFromColumns()).isEqualTo("0");
        script("db/rollback/V13__undo_drop_soqm_development_materials.sql");
        assertThat(materialsColumns()).isEqualTo("1");
        assertThat(single("SELECT COUNT(*) FROM controls WHERE soqm_development_materials IS NOT NULL")).isEqualTo("0");
        script("db/rollback/V12__undo_user_deletion.sql");
        assertThat(single("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = '" + schema
                + "' AND table_name = 'controls' AND column_name LIKE 'created_by_%'")).isEqualTo("0");
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

    /**
     * The version before this one (V12) with SoQM Development Materials filled in, Documents attachments, and a
     * Changelog entry naming the field: V13 drops the column only; the rollback brings it back empty.
     */
    @Test
    void dataOfTheVersionBefore_migratesToV13_theMaterialsColumnGoes_andItsRollbackBringsItBackEmpty() throws Exception {
        flyway("12").migrate();
        execute("INSERT INTO controls (control_id, control_frequency, performance_status, control_status, "
                + "attachment_documents_path, control_operation_date, control_operation_deadline, deadline, "
                + "soqm_development_materials, created_by_email, created_by_name) VALUES "
                + "('HR-V12-1/FY27/Central/OCT', 'Monthly', 'IN_PROGRESS', 'ACTIVE', 'plan.docx;notes.pdf', "
                + "DATE '2026-10-12', DATE '2026-10-26', DATE '2026-10-26', 'Available', 'v12@release.test', 'V12 Person'), "
                + "('HR-V12-2/FY27/Central/OCT', 'Quarterly', 'COMPLETED', 'ACTIVE', NULL, "
                + "DATE '2026-10-17', DATE '2026-10-31', DATE '2026-10-31', 'Not Available', NULL, NULL), "
                + "('HR-V12-3', 'Annual', 'DRAFT', NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL)");
        execute("INSERT INTO admin_audit_log (control_id, created_at, action_type, control_control_id, "
                + "action_description, admin_email, admin_name, changed_fields, previous_values, new_values) "
                + "SELECT id, TIMESTAMP '2026-10-13 10:00:00', 'EDIT', control_id, 'Edit Control', 'soqm@release.test', "
                + "'SoQM', '[\"SoQM Development Materials\"]', '{\"SoQM Development Materials\":null}', "
                + "'{\"SoQM Development Materials\":\"Available\"}' FROM controls WHERE control_id = 'HR-V12-1/FY27/Central/OCT'");

        // To V13 only: V14 has its own test below
        Flyway latest = flyway("13");
        latest.migrate();
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("13");

        assertThat(materialsColumns()).isEqualTo("0");
        assertThat(rows("SELECT control_id, performance_status || '|' || COALESCE(attachment_documents_path, '-') || '|' "
                + "|| COALESCE(CAST(control_operation_deadline AS VARCHAR), '-') || '|' || COALESCE(created_by_name, '-') "
                + "FROM controls WHERE control_id LIKE 'HR-V12-%' ORDER BY control_id"))
                .containsExactly(
                        Map.entry("HR-V12-1/FY27/Central/OCT", "IN_PROGRESS|plan.docx;notes.pdf|2026-10-26|V12 Person"),
                        Map.entry("HR-V12-2/FY27/Central/OCT", "COMPLETED|-|2026-10-31|-"),
                        Map.entry("HR-V12-3", "DRAFT|-|-|-"));
        // The Changelog keeps the entry that names the field, as it was
        assertThat(single("SELECT new_values FROM admin_audit_log WHERE control_control_id = 'HR-V12-1/FY27/Central/OCT'"))
                .isEqualTo("{\"SoQM Development Materials\":\"Available\"}");

        script("db/rollback/V13__undo_drop_soqm_development_materials.sql");
        assertThat(materialsColumns()).isEqualTo("1");
        assertThat(single("SELECT is_nullable || '|' || data_type FROM information_schema.columns WHERE table_schema = '"
                + schema + "' AND table_name = 'controls' AND column_name = 'soqm_development_materials'"))
                .isEqualTo("YES|character varying");
        // The values are not restored
        assertThat(single("SELECT COUNT(*) FROM controls WHERE soqm_development_materials IS NOT NULL")).isEqualTo("0");
        assertThat(single("SELECT COUNT(*) FROM controls WHERE control_id LIKE 'HR-V12-%'")).isEqualTo("3");
    }

    /**
     * The version before this one (V13) with controls and a Changelog: V14 adds created_from_control_id, empty on every
     * row, linked to controls (a removed source leaves the link empty); its rollback drops it and keeps the rows.
     */
    /**
     * The version before this one (V14) with controls and their earlier free text in other_related_controls: V15 adds
     * control_related_controls, empty; a link names two controls, once per pair, never a control itself, and goes with
     * either control; the rollback drops the table and keeps the controls and their text. To V15 only: V16 has its own
     * test below.
     */
    @Test
    void dataOfTheVersionBefore_migratesToV15_noLinks_theTableKeepsThemRight_andItsRollbackDropsIt() throws Exception {
        flyway("14").migrate();
        execute("INSERT INTO controls (control_id, control_frequency, performance_status, control_status, "
                + "other_related_controls) VALUES "
                + "('HR-V14-1/FY27/Central/OCT', 'Monthly', 'IN_PROGRESS', 'ACTIVE', 'HR-12, see the list'), "
                + "('EP-V14-2/FY27/Central', 'Annual', 'COMPLETED', 'ACTIVE', NULL), "
                + "('HR-V14-3/FY27/Central/1Q', 'Quarterly', 'DRAFT', NULL, NULL)");

        Flyway latest = flyway("15");
        latest.migrate();
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo("15");
        assertThat(relatedTables()).isEqualTo("1");
        assertThat(single("SELECT COUNT(*) FROM control_related_controls")).isEqualTo("0");
        assertThat(single("SELECT other_related_controls FROM controls WHERE control_id = 'HR-V14-1/FY27/Central/OCT'"))
                .isEqualTo("HR-12, see the list");

        String first = single("SELECT id FROM controls WHERE control_id = 'HR-V14-1/FY27/Central/OCT'");
        String second = single("SELECT id FROM controls WHERE control_id = 'EP-V14-2/FY27/Central'");
        String third = single("SELECT id FROM controls WHERE control_id = 'HR-V14-3/FY27/Central/1Q'");
        execute("INSERT INTO control_related_controls (control_id, related_control_id, sort_order) VALUES "
                + "(" + first + ", " + second + ", 0), (" + first + ", " + third + ", 1), (" + second + ", " + first + ", 0)");
        assertThat(rows("SELECT CAST(related_control_id AS VARCHAR), CAST(sort_order AS VARCHAR) FROM control_related_controls "
                + "WHERE control_id = " + first + " ORDER BY sort_order"))
                .containsExactly(Map.entry(second, "0"), Map.entry(third, "1"));
        // The same pair twice, a control linked to itself, a control that is not there
        assertThatThrownBy(() -> execute("INSERT INTO control_related_controls (control_id, related_control_id, sort_order) "
                + "VALUES (" + first + ", " + second + ", 5)")).isInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> execute("INSERT INTO control_related_controls (control_id, related_control_id, sort_order) "
                + "VALUES (" + first + ", " + first + ", 5)")).isInstanceOf(java.sql.SQLException.class);
        assertThatThrownBy(() -> execute("INSERT INTO control_related_controls (control_id, related_control_id, sort_order) "
                + "VALUES (" + first + ", 987654321, 5)")).isInstanceOf(java.sql.SQLException.class);
        // A removed control takes the links to it and from it along
        execute("DELETE FROM controls WHERE id = " + third);
        assertThat(single("SELECT COUNT(*) FROM control_related_controls")).isEqualTo("2");
        execute("DELETE FROM controls WHERE id = " + second);
        assertThat(single("SELECT COUNT(*) FROM control_related_controls")).isEqualTo("0");

        script("db/rollback/V15__undo_control_related_controls.sql");
        assertThat(relatedTables()).isEqualTo("0");
        assertThat(single("SELECT COUNT(*) FROM controls WHERE control_id LIKE '%-V14-%'")).isEqualTo("1");
        assertThat(single("SELECT other_related_controls FROM controls WHERE id = " + first)).isEqualTo("HR-12, see the list");
    }

    /**
     * The version before this one (V15) with the earlier free text in other_related_controls, links and a Changelog
     * entry naming the text: V16 drops the column only; the rollback brings it back empty.
     */
    @Test
    void dataOfTheVersionBefore_migratesToV16_theEarlierTextColumnGoes_andItsRollbackBringsItBackEmpty() throws Exception {
        flyway("15").migrate();
        execute("INSERT INTO controls (control_id, control_frequency, performance_status, control_status, "
                + "other_related_controls) VALUES "
                + "('HR-V15-1/FY27/Central/OCT', 'Monthly', 'IN_PROGRESS', 'ACTIVE', 'HR-12, see the list'), "
                + "('EP-V15-2/FY27/Central', 'Annual', 'COMPLETED', 'ACTIVE', NULL)");
        String first = single("SELECT id FROM controls WHERE control_id = 'HR-V15-1/FY27/Central/OCT'");
        String second = single("SELECT id FROM controls WHERE control_id = 'EP-V15-2/FY27/Central'");
        execute("INSERT INTO control_related_controls (control_id, related_control_id, sort_order) VALUES "
                + "(" + first + ", " + second + ", 0)");
        execute("INSERT INTO admin_audit_log (control_id, created_at, action_type, control_control_id, "
                + "action_description, admin_email, admin_name, changed_fields, previous_values, new_values) "
                + "VALUES (" + first + ", TIMESTAMP '2026-10-15 10:00:00', 'EDIT', 'HR-V15-1/FY27/Central/OCT', "
                + "'Edit Control', 'soqm@release.test', 'SoQM', '[\"Other Related Controls (earlier text)\"]', "
                + "'{\"Other Related Controls (earlier text)\":null}', "
                + "'{\"Other Related Controls (earlier text)\":\"HR-12, see the list\"}')");

        // To V16 only: V18 brings the column back (its own test below)
        Flyway v16 = flyway("16");
        v16.migrate();
        assertThat(v16.info().current().getVersion().getVersion()).isEqualTo("16");

        assertThat(relatedTextColumns()).isEqualTo("0");
        assertThat(single("SELECT COUNT(*) FROM controls WHERE control_id LIKE '%-V15-%'")).isEqualTo("2");
        // The links and the Changelog entry stay as they were
        assertThat(single("SELECT CAST(related_control_id AS VARCHAR) FROM control_related_controls WHERE control_id = "
                + first)).isEqualTo(second);
        assertThat(single("SELECT new_values FROM admin_audit_log WHERE control_control_id = 'HR-V15-1/FY27/Central/OCT'"))
                .isEqualTo("{\"Other Related Controls (earlier text)\":\"HR-12, see the list\"}");

        script("db/rollback/V16__undo_drop_other_related_controls_text.sql");
        assertThat(relatedTextColumns()).isEqualTo("1");
        assertThat(single("SELECT is_nullable || '|' || data_type FROM information_schema.columns WHERE table_schema = '"
                + schema + "' AND table_name = 'controls' AND column_name = 'other_related_controls'"))
                .isEqualTo("YES|character varying");
        // The texts are not restored
        assertThat(single("SELECT COUNT(*) FROM controls WHERE other_related_controls IS NOT NULL")).isEqualTo("0");
    }

    /**
     * The version before this one (V16) with controls and their links: V17 adds the empty list Other Related Controls
     * is chosen from, one row per Control ID by its key; the rollback drops it and keeps the controls and links.
     */
    @Test
    void dataOfTheVersionBefore_migratesToV17_theListIsEmpty_oneRowPerKey_andItsRollbackDropsIt() throws Exception {
        flyway("16").migrate();
        execute("INSERT INTO controls (control_id, control_frequency, performance_status, control_status) VALUES "
                + "('HR-V16-1/FY27/Central/OCT', 'Monthly', 'IN_PROGRESS', 'ACTIVE'), "
                + "('EP-V16-2/FY27/Central', 'Annual', 'COMPLETED', 'ACTIVE')");
        String first = single("SELECT id FROM controls WHERE control_id = 'HR-V16-1/FY27/Central/OCT'");
        String second = single("SELECT id FROM controls WHERE control_id = 'EP-V16-2/FY27/Central'");
        execute("INSERT INTO control_related_controls (control_id, related_control_id, sort_order) VALUES "
                + "(" + first + ", " + second + ", 0)");

        Flyway v17 = flyway("17");
        v17.migrate();
        assertThat(v17.info().current().getVersion().getVersion()).isEqualTo("17");

        assertThat(refTables()).isEqualTo("1");
        assertThat(single("SELECT COUNT(*) FROM related_control_ref")).isEqualTo("0");
        execute("INSERT INTO related_control_ref (control_id, control_key) VALUES ('HR-CTRL-1', 'hr-ctrl-1')");
        assertThat(single("SELECT CAST(active AS VARCHAR) || '|' || (created_at IS NOT NULL) FROM related_control_ref"))
                .isEqualTo("true|true");
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> execute("INSERT INTO related_control_ref "
                + "(control_id, control_key) VALUES ('hr-ctrl-1', 'hr-ctrl-1')")).isInstanceOf(java.sql.SQLException.class);
        assertThat(single("SELECT COUNT(*) FROM control_related_controls")).isEqualTo("1");

        script("db/rollback/V17__undo_related_control_ref.sql");
        assertThat(refTables()).isEqualTo("0");
        assertThat(single("SELECT COUNT(*) FROM controls WHERE control_id LIKE '%-V16-%'")).isEqualTo("2");
        assertThat(single("SELECT COUNT(*) FROM control_related_controls")).isEqualTo("1");
    }

    /**
     * The version before this one (V17) with linked controls and an IT application: V18 writes the links as the linked
     * controls' current Control IDs, in their order, into other_related_controls (TEXT) and makes it_applications TEXT;
     * the rollback drops the column (the links stay) and brings it_applications back to VARCHAR(255), cut to 255.
     */
    @Test
    void dataOfTheVersionBefore_migratesToV18_theLinksBecomeControlIds_andItsRollbackKeepsTheLinks() throws Exception {
        flyway("17").migrate();
        execute("INSERT INTO controls (control_id, control_frequency, performance_status, control_status, it_applications)"
                + " VALUES ('HR-V17-1/FY27/Central/OCT', 'Monthly', 'IN_PROGRESS', 'ACTIVE', 'KICS'), "
                + "('EP-V17-2/FY27/Central', 'Annual', 'COMPLETED', 'ACTIVE', NULL), "
                + "('KDN-V17-3', 'Quarterly', 'REVIEW', 'ACTIVE', NULL)");
        String first = single("SELECT id FROM controls WHERE control_id = 'HR-V17-1/FY27/Central/OCT'");
        String second = single("SELECT id FROM controls WHERE control_id = 'EP-V17-2/FY27/Central'");
        String third = single("SELECT id FROM controls WHERE control_id = 'KDN-V17-3'");
        execute("INSERT INTO control_related_controls (control_id, related_control_id, sort_order) VALUES "
                + "(" + first + ", " + second + ", 1), (" + first + ", " + third + ", 0)");

        Flyway latest = flyway(null);
        latest.migrate();
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo(LATEST);

        assertThat(single("SELECT other_related_controls FROM controls WHERE id = " + first))
                .isEqualTo("KDN-V17-3; EP-V17-2/FY27/Central");
        assertThat(single("SELECT COALESCE(other_related_controls, '-') FROM controls WHERE id = " + second)).isEqualTo("-");
        assertThat(single("SELECT data_type FROM information_schema.columns WHERE table_schema = '" + schema
                + "' AND table_name = 'controls' AND column_name = 'it_applications'")).isEqualTo("text");
        assertThat(single("SELECT data_type FROM information_schema.columns WHERE table_schema = '" + schema
                + "' AND table_name = 'controls' AND column_name = 'other_related_controls'")).isEqualTo("text");
        assertThat(single("SELECT it_applications FROM controls WHERE id = " + first)).isEqualTo("KICS");
        execute("UPDATE controls SET it_applications = '" + "x".repeat(300) + "' WHERE id = " + second);

        script("db/rollback/V18__undo_related_fields_text.sql");
        assertThat(relatedTextColumns()).isEqualTo("0");
        assertThat(single("SELECT COUNT(*) FROM control_related_controls WHERE control_id = " + first)).isEqualTo("2");
        assertThat(single("SELECT data_type || '|' || character_maximum_length FROM information_schema.columns "
                + "WHERE table_schema = '" + schema + "' AND table_name = 'controls' AND column_name = 'it_applications'"))
                .isEqualTo("character varying|255");
        assertThat(single("SELECT LENGTH(it_applications) FROM controls WHERE id = " + second)).isEqualTo("255");
    }

    @Test
    void dataOfTheVersionBefore_migratesToV14_createdFromIsEmpty_andItsRollbackDropsIt() throws Exception {
        flyway("13").migrate();
        execute("INSERT INTO controls (control_id, control_frequency, performance_status, control_status, "
                + "control_operation_date, control_operation_deadline, next_control_operation_date, deadline) VALUES "
                + "('HR-V13-1/FY27/Central/OCT', 'Monthly', 'COMPLETED', 'ACTIVE', DATE '2026-10-12', DATE '2026-10-26', "
                + "DATE '2026-11-12', DATE '2026-10-26'), "
                + "('HR-V13-2/FY27/Central/1Q', 'Quarterly', 'IN_PROGRESS', 'ACTIVE', DATE '2026-10-05', DATE '2026-10-19', "
                + "DATE '2027-01-05', DATE '2026-10-19')");

        Flyway latest = flyway(null);
        latest.migrate();
        assertThat(latest.info().current().getVersion().getVersion()).isEqualTo(LATEST);

        assertThat(createdFromColumns()).isEqualTo("1");
        assertThat(single("SELECT is_nullable || '|' || data_type FROM information_schema.columns WHERE table_schema = '"
                + schema + "' AND table_name = 'controls' AND column_name = 'created_from_control_id'"))
                .isEqualTo("YES|bigint");
        assertThat(single("SELECT COUNT(*) FROM controls WHERE control_id LIKE 'HR-V13-%' AND created_from_control_id IS NULL"))
                .isEqualTo("2");
        // A next cycle made from the first; the source removed, the link goes and the cycle stays (V14 only: V15 has its
        // own test above)
        execute("INSERT INTO controls (control_id, control_frequency, performance_status, created_from_control_id) "
                + "SELECT 'HR-V13-1/FY27/Central/NOV', 'Monthly', 'IN_PROGRESS', id FROM controls "
                + "WHERE control_id = 'HR-V13-1/FY27/Central/OCT'");
        assertThat(single("SELECT COUNT(*) FROM controls n JOIN controls s ON s.id = n.created_from_control_id "
                + "WHERE n.control_id = 'HR-V13-1/FY27/Central/NOV'")).isEqualTo("1");
        execute("DELETE FROM controls WHERE control_id = 'HR-V13-1/FY27/Central/OCT'");
        assertThat(single("SELECT COALESCE(CAST(created_from_control_id AS VARCHAR), '-') FROM controls "
                + "WHERE control_id = 'HR-V13-1/FY27/Central/NOV'")).isEqualTo("-");

        script("db/rollback/V14__undo_control_created_from.sql");
        assertThat(createdFromColumns()).isEqualTo("0");
        assertThat(single("SELECT COUNT(*) FROM controls WHERE control_id LIKE 'HR-V13-%'")).isEqualTo("2");
    }

    // ---- helpers ----

    private String relatedTables() throws Exception {
        return single("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '" + schema
                + "' AND table_name = 'control_related_controls'");
    }

    private String refTables() throws Exception {
        return single("SELECT COUNT(*) FROM information_schema.tables WHERE table_schema = '" + schema
                + "' AND table_name = 'related_control_ref'");
    }

    private String createdFromColumns() throws Exception {
        return single("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = '" + schema
                + "' AND table_name = 'controls' AND column_name = 'created_from_control_id'");
    }

    private String materialsColumns() throws Exception {
        return single("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = '" + schema
                + "' AND table_name = 'controls' AND column_name = 'soqm_development_materials'");
    }

    private String relatedTextColumns() throws Exception {
        return single("SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = '" + schema
                + "' AND table_name = 'controls' AND column_name = 'other_related_controls'");
    }

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
