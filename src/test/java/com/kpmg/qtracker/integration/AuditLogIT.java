package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.service.SystemAdmins;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MvcResult;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;

/**
 * The Audit trail section of the Admin Panel (business decision of 2026-11-10): what people really did - a user created
 * and changed, a control edited, a file uploaded, a workflow step, a control deactivated - and what the application did,
 * in three groups with who, when, the target and before / after; filters and pages; SoQM Admin Team and System Admin
 * Team only.
 */
class AuditLogIT extends ReleaseScenarioSupport {

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void clearList() {
        SystemAdmins.replace(List.of());
    }

    @Test
    void whatPeopleAndTheSystemDid_inThreeGroups_withBeforeAndAfter() throws Exception {
        User soqm = soqm("al-soqm");
        MockHttpSession session = login(soqm);

        // Users: a person created, then their role and status changed
        String newMail = ("al-person-" + RUN + "@release.test").toLowerCase();
        assertThat(status(session, post("/api/users").param("email", newMail).param("displayName", "Audit Person")
                .param("role", "USER").param("visibility", "MY").param("access", "READ_ONLY"))).isEqualTo(200);
        User person = userRepository.findByMail(newMail).orElseThrow();
        assertThat(status(session, post("/api/users/{id}/access", person.getId()).param("role", "USER")
                .param("visibility", "ALL").param("access", "EDIT").param("enabled", "false"))).isEqualTo(200);

        // Controls: edited, a file uploaded, a workflow step; another one deactivated
        Control control = control("HR-AL", "IN_PROGRESS", people().facilitator(myEdit("al-fac")).operator(myEdit("al-op"))
                .soqm(soqm).owner(myEdit("al-po")));
        assertThat(status(session, put("/api/controls/{id}", control.getId()).contentType("application/json")
                .content("{\"controlDescription\":\"Changed for the audit\"}"))).isEqualTo(200);
        assertThat(status(session, multipart("/api/attachments/upload/{id}", control.getId())
                .file(new MockMultipartFile("attachmentDocuments", "evidence-al.pdf", "application/pdf",
                        "%PDF-1.4 al".getBytes(StandardCharsets.UTF_8))))).isEqualTo(200);
        assertThat(status(session, post("/api/workflow/move").param("controlId", String.valueOf(control.getId()))
                .param("targetStatus", "REVIEW").param("comments", "Moved for the audit"))).isEqualTo(200);
        Control gone = control("HR-AL-GONE", "DRAFT", people().soqm(soqm));
        assertThat(status(session, post("/api/controls/{id}/deactivate", gone.getId()).contentType("application/json")
                .content("{\"confirmControlId\":\"" + gone.getControlId() + "\"}"))).isEqualTo(200);

        // System: the application's own entry
        String purge = "Data older than 7 years deleted " + RUN;
        jdbc.update("INSERT INTO admin_audit_log (admin_email, admin_name, action_type, action_description, created_at)"
                + " VALUES ('system', 'Data retention (scheduler)', 'DATA_RETENTION_PURGE', ?, ?)", purge,
                Timestamp.valueOf(LocalDateTime.now()));

        // Users group: the person as the target, by name; the change before and after
        String users = body(call(session, get("/admin/audit").param("q", newMail)));
        assertThat(users).contains("aria-current=\"page\"", ">Audit trail", "User created", "Access changed",
                "Audit Person", newMail, soqm.getDisplayName());
        assertThat(rows(users)).isEqualTo(2);
        assertThat(users.replaceAll("\\s+", " ")).contains("data-group=\"USERS\"")
                .containsPattern("is-field\">Status</td> <td class=\"is-before\">Active</td> <td class=\"is-after\">Inactive</td>");

        // Controls group: the edit with its values, the file, the step, the deactivated control with its snapshot
        String controls = body(call(session, get("/admin/audit").param("q", control.getControlId())));
        assertThat(controls).contains("Control edited", "File uploaded", "Workflow step",
                "href=\"/view-control/" + control.getId() + "\"", "Release test control", "Changed for the audit");
        assertThat(rows(controls)).isGreaterThanOrEqualTo(3);
        String deactivated = body(call(session, get("/admin/audit").param("q", gone.getControlId())));
        assertThat(deactivated).contains("Control deactivated", "title=\"The control no longer exists\"",
                gone.getControlId(), "is-field\">Status</td>");

        // System group: by "System"
        String system = body(call(session, get("/admin/audit").param("q", purge)));
        assertThat(system).contains("Data retention", ">System<", "bi-gear", "data-group=\"SYSTEM\"");
        assertThat(rows(system)).isEqualTo(1);

        // The cards count each group; a group and an action narrow the rows
        String byControl = body(call(session, get("/admin/audit").param("q", control.getControlId()).param("group", "USERS")));
        assertThat(rows(byControl)).isZero();
        String files = body(call(session, get("/admin/audit").param("q", control.getControlId()).param("action", "FILES")));
        assertThat(rows(files)).isEqualTo(1);
        assertThat(files).contains("File uploaded").doesNotContain("Control edited</span>");
        // A period before them: none
        assertThat(body(call(session, get("/admin/audit").param("q", newMail).param("from", "2020-01-01")
                .param("to", "2020-01-31")))).contains("No entries in this period");
        // The same look as the other sections: English calendar, "2 hours ago", the shared styles
        assertThat(users).contains("data-date-picker", "/js/time-ago.js", "data-now=\"", "ap-table", "ap-card-count")
                .doesNotContain("type=\"date\"");
    }

    @Test
    void manyEntries_comeInPagesOfFifty_andOnlySoqmAndSystemAdminOpenThePage() throws Exception {
        User soqm = soqm("al2-soqm");
        String tag = "al2-tag-" + RUN;
        LocalDateTime now = LocalDateTime.now().withNano(0);
        for (int i = 0; i < 55; i++) {
            jdbc.update("INSERT INTO admin_audit_log (admin_email, admin_name, action_type, action_description, created_at)"
                    + " VALUES (?, ?, 'EDIT', ?, ?)", soqm.getMail(), soqm.getDisplayName(), tag + " " + i,
                    Timestamp.valueOf(now.minusMinutes(i)));
        }
        MockHttpSession session = login(soqm);
        String first = body(call(session, get("/admin/audit").param("q", tag)));
        assertThat(rows(first)).isEqualTo(50);
        assertThat(first).contains("Showing 1-50 of 55", "1 / 2");
        assertThat(rows(body(call(session, get("/admin/audit").param("q", tag).param("page", "2"))))).isEqualTo(5);

        User admin = person("al2-sysadmin", AccessLevel.SOQM, AccessScope.ALL);
        SystemAdmins.replace(List.of(admin.getMail()));
        assertThat(call(login(admin), get("/admin/audit")).getModelAndView().getViewName()).isEqualTo("admin-audit");
        for (User user : List.of(myEdit("al2-user"), myRead("al2-ro"), allEdit("al2-all"), kdn("al2-kdn"))) {
            MvcResult refused = call(login(user), get("/admin/audit"));
            assertThat(refused.getResponse().getRedirectedUrl()).as(user.getMail()).isEqualTo("/");
        }
    }

    private static int rows(String html) {
        int count = 0;
        for (int at = html.indexOf("class=\"audit-row\""); at >= 0; at = html.indexOf("class=\"audit-row\"", at + 1)) {
            count++;
        }
        return count;
    }
}
