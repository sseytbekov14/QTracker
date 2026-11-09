package com.kpmg.qtracker.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.kpmg.qtracker.entity.AdminAuditLog;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.ControlDeactivation;
import com.kpmg.qtracker.service.ControlDependents;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * Deactivate control (business decision of 2026-11-06): SoQM Admin Team removes a control for good, in any status - its
 * row, every row of it (ControlDependents) and its files on disk -, after typing its Control ID (any case, spaces at
 * the ends ignored), and the Admin Panel keeps a CONTROL_DEACTIVATED entry with a snapshot of it. Everyone else is
 * refused (403) and a wrong ID is refused (400), both without deleting anything.
 */
class ControlDeactivationIT extends ReleaseScenarioSupport {

    private static final List<String> STATUSES = List.of(
            "DRAFT", "IN_PROGRESS", "REVIEW", "SOQM_HEAD_REVIEW", "PROCESS_OWNER_REVIEW", "COMPLETED");
    private static final Path UPLOADS = Path.of("target", "it-uploads-release");

    @Autowired
    JdbcTemplate jdbc;

    @Test
    void inEveryStatus_soqmDeactivates_theControlItsRowsAndFilesGo_theSnapshotStays() throws Exception {
        User soqm = soqm("dz-soqm");
        User fac = myEdit("dz-fac");
        User op = myEdit("dz-op");
        User po = myEdit("dz-po");
        User viewer = myRead("dz-viewer");
        MockHttpSession session = login(soqm);

        for (String status : STATUSES) {
            Control control = control("HR-DZ-" + status.charAt(0), status,
                    people().facilitator(fac).operator(op).soqm(soqm).owner(po).shared(viewer).creator(soqm));
            Long id = control.getId();
            String controlId = control.getControlId();
            Path folder = UPLOADS.resolve(controlId.replaceAll("[^a-zA-Z0-9._-]", "_"));
            // A Documentation file through the page's upload, and a row in every table that holds the control's id
            MvcResult upload = call(session, multipart("/api/attachments/upload/{id}", id)
                    .file(new MockMultipartFile("attachmentDocuments", "documentation.pdf", "application/pdf",
                            "%PDF-1.4 documentation".getBytes(StandardCharsets.UTF_8)))
                    .param("editReason", "Before deactivation"));
            assertThat(upload.getResponse().getStatus()).as(status + " upload " + body(upload)).isEqualTo(200);
            everyRowOf(id, soqm);
            // A next cycle made from it keeps its row and loses only the link
            Control next = control("HR-DZ-NEXT", "DRAFT", people().soqm(soqm));
            jdbc.update("UPDATE controls SET created_from_control_id = ? WHERE id = ?", id, next.getId());
            Map<String, Integer> before = ControlDependents.count(jdbc, List.of(id));
            for (String table : ControlDependents.TABLES) {
                assertThat(before.get(table)).as(status + " " + table + " before").isPositive();
            }
            try (var files = Files.list(folder)) {
                assertThat(files.count()).as(status + " files before").isGreaterThanOrEqualTo(2);
            }

            // Typed in another case, with spaces around it
            MvcResult done = callJson(session, post("/api/controls/{id}/deactivate", id),
                    "{\"confirmControlId\":\"  " + controlId.toLowerCase() + "  \"}");

            assertThat(done.getResponse().getStatus()).as(status + " " + body(done)).isEqualTo(200);
            assertThat(json(done).path("success").asBoolean()).isTrue();
            assertThat(controlRepository.findById(id)).as(status).isEmpty();
            Map<String, Integer> after = ControlDependents.count(jdbc, List.of(id));
            for (String table : ControlDependents.TABLES) {
                assertThat(after.get(table)).as(status + " " + table + " after").isZero();
            }
            assertThat(Files.exists(folder)).as(status + " the folder and its files").isFalse();
            assertThat(jdbc.queryForObject("SELECT created_from_control_id FROM controls WHERE id = ?", Long.class,
                    next.getId())).isNull();

            // The audit entry: who, when, which control, its status, its people, as JSON; not tied to the gone id
            AdminAuditLog entry = deactivationEntry(controlId);
            assertThat(entry.getAdminEmail()).isEqualTo(soqm.getMail());
            assertThat(entry.getAdminName()).isEqualTo(soqm.getDisplayName());
            assertThat(entry.getControlId()).isNull();
            assertThat(entry.getCreatedAt()).isAfter(LocalDateTime.now().minusDays(1));
            JsonNode snapshot = objectMapper.readTree(entry.getNewValues());
            assertThat(snapshot.path("id").asLong()).isEqualTo(id);
            assertThat(snapshot.path("controlId").asText()).isEqualTo(controlId);
            assertThat(snapshot.path("description").asText()).isEqualTo("Release test control");
            assertThat(snapshot.path("performanceStatus").asText()).isEqualTo(status);
            assertThat(snapshot.path("deactivatedBy").asText()).isEqualTo(soqm.getMail());
            JsonNode roles = snapshot.path("roles");
            assertThat(roles.path("Facilitator").get(0).path("email").asText()).isEqualTo(fac.getMail());
            assertThat(roles.path("Facilitator").get(0).path("name").asText()).isEqualTo(fac.getDisplayName());
            assertThat(roles.path("Control Operator").get(0).path("email").asText()).isEqualTo(op.getMail());
            assertThat(roles.path("SoQM Admin Team").get(0).path("email").asText()).isEqualTo(soqm.getMail());
            assertThat(roles.path("Process Owner").get(0).path("email").asText()).isEqualTo(po.getMail());
            assertThat(roles.path("Control Shared With").get(0).path("email").asText()).isEqualTo(viewer.getMail());
            assertThat(snapshot.path("files").toString()).contains("evidence").contains("documentation");
        }

        // The Admin Panel: its own event type in the filter, the snapshot readable
        String admin = body(call(session, get("/admin/users")));
        assertThat(admin).contains("value=\"CONTROL_DEACTIVATED\"", "Deactivated controls (")
                .contains("<summary>Snapshot</summary>", "Facilitator: " + fac.getDisplayName() + " (" + fac.getMail() + ")",
                        "Status: Completed", "Status: Draft", "Description: Release test control");
    }

    @Test
    void everyoneButSoqm_isRefused_andNothingIsDeleted() throws Exception {
        User soqm = soqm("dz2-soqm");
        User fac = myEdit("dz2-fac");
        User op = myEdit("dz2-op");
        User po = myEdit("dz2-po");
        User shared = myEdit("dz2-shared");
        User creator = myEdit("dz2-creator");
        List<User> others = List.of(fac, op, po, shared, creator, allEdit("dz2-all"), myRead("dz2-read"),
                allRead("dz2-allread"), kdn("dz2-kdn"));
        int entries = deactivationEntries();

        for (String status : STATUSES) {
            Control control = control("KDN-DZ2", status,
                    people().facilitator(fac).operator(op).soqm(soqm).owner(po).shared(shared).creator(creator));
            for (User user : others) {
                MvcResult refused = callJson(login(user), post("/api/controls/{id}/deactivate", control.getId()),
                        "{\"confirmControlId\":\"" + control.getControlId() + "\"}");
                assertThat(refused.getResponse().getStatus()).as(status + " " + user.getMail()).isEqualTo(403);
            }
            assertThat(controlRepository.findById(control.getId())).as(status).isPresent();
            assertThat(Files.exists(UPLOADS.resolve(control.getControlId()).resolve(control.getAttachmentDetailsPath())))
                    .as(status + " file kept").isTrue();
            // Without a session: the sign-in, nothing deleted
            MvcResult anonymous = callJson(null, post("/api/controls/{id}/deactivate", control.getId()),
                    "{\"confirmControlId\":\"" + control.getControlId() + "\"}");
            assertThat(anonymous.getResponse().getStatus()).isNotEqualTo(200);
            assertThat(controlRepository.findById(control.getId())).isPresent();
        }
        assertThat(deactivationEntries()).isEqualTo(entries);
    }

    @Test
    void aWrongOrMissingControlId_isRefused_andNothingIsDeleted() throws Exception {
        User soqm = soqm("dz3-soqm");
        MockHttpSession session = login(soqm);
        Control control = control("HR-DZ3", "IN_PROGRESS", people().soqm(soqm));
        int entries = deactivationEntries();

        for (String typed : List.of("{\"confirmControlId\":\"" + control.getControlId() + "-X\"}",
                "{\"confirmControlId\":\"HR-DZ3\"}", "{\"confirmControlId\":\"   \"}", "{}")) {
            MvcResult refused = callJson(session, post("/api/controls/{id}/deactivate", control.getId()), typed);
            assertThat(refused.getResponse().getStatus()).as(typed).isEqualTo(400);
            assertThat(json(refused).path("message").asText()).as(typed).contains("does not match");
        }
        assertThat(controlRepository.findById(control.getId())).isPresent();
        assertThat(deactivationEntries()).isEqualTo(entries);
        assertThat(status(session, post("/api/controls/{id}/deactivate", Long.MAX_VALUE)
                .contentType("application/json").content("{\"confirmControlId\":\"X\"}"))).isEqualTo(404);
    }

    /**
     * The button is in View Control's More actions only (2026-11-09: no longer next to Download on Performance Cycle),
     * for SoQM Admin Team, in every status.
     */
    @Test
    void theButton_isOfferedToSoqmOnly_inViewControl_inEveryStatus_notOnPerformanceCycle() throws Exception {
        User soqm = soqm("dz4-soqm");
        User fac = myEdit("dz4-fac");
        MockHttpSession soqmSession = login(soqm);
        MockHttpSession facSession = login(fac);
        for (String status : STATUSES) {
            Control control = control("HR-DZ4", status, people().facilitator(fac).soqm(soqm));
            String viewControl = "/view-control/" + control.getId();
            assertThat(body(call(soqmSession, get(viewControl)))).as(status + " View Control")
                    .contains("id=\"deactivateControlBtn\"", "data-deactivate-control",
                            "data-control-id=\"" + control.getControlId() + "\"", "/js/deactivate-control.js");
            assertThat(body(call(facSession, get(viewControl)))).as(status + " View Control, Facilitator")
                    .doesNotContain("deactivateControlBtn").doesNotContain("deactivate-control.js");
            for (MockHttpSession session : List.of(soqmSession, facSession)) {
                assertThat(body(call(session, get("/performance-cycle/" + control.getId()))))
                        .as(status + " Performance Cycle")
                        .doesNotContain("deactivateControlBtn").doesNotContain("deactivate-control.js");
            }
        }
    }

    private void everyRowOf(Long id, User user) {
        LocalDateTime now = LocalDateTime.now();
        jdbc.update("INSERT INTO workflow_steps (control_id) VALUES (?)", id);
        jdbc.update("INSERT INTO workflow_history (control_id, on_behalf, performed_by_email, comments, created_at)"
                + " VALUES (?, FALSE, ?, 'Please redo', ?)", id, user.getMail(), Timestamp.valueOf(now));
        jdbc.update("INSERT INTO notifications (user_id, control_id, type, title, message, is_read, created_at)"
                + " VALUES (?, ?, 'REMINDER', 't', 'm', FALSE, ?)", user.getId(), id, Timestamp.valueOf(now));
        // A reminder still to be sent
        jdbc.update("INSERT INTO email_outbox (control_id, template_key, recipient_user_id, recipient_email, subject,"
                        + " body_html, importance, dedupe_key, status, attempts, created_at)"
                        + " VALUES (?, 'T3', ?, ?, 's', '<p>b</p>', 'NORMAL', ?, 'PENDING', 0, ?)",
                id, user.getId(), user.getMail(), "dz-" + RUN + "-" + id, Timestamp.valueOf(now));
        jdbc.update("INSERT INTO admin_audit_log (admin_email, action_type, control_id, action_description, created_at)"
                + " VALUES (?, 'EDIT', ?, 'an edit', ?)", user.getMail(), id, Timestamp.valueOf(now));
    }

    private AdminAuditLog deactivationEntry(String controlId) {
        return auditRepository.findTop100ByActionTypeOrderByCreatedAtDesc(ControlDeactivation.AUDIT_ACTION).stream()
                .filter(log -> controlId.equals(log.getControlControlId()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("no " + ControlDeactivation.AUDIT_ACTION + " entry for " + controlId));
    }

    private int deactivationEntries() {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM admin_audit_log WHERE action_type = ?", Integer.class,
                ControlDeactivation.AUDIT_ACTION);
        return count == null ? 0 : count;
    }
}
