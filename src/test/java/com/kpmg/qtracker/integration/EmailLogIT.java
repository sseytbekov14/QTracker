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
import org.springframework.test.web.servlet.MvcResult;

import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

/**
 * The E-mails page of the Admin Panel (business decision of 2026-11-10): SoQM Admin Team and System Admin Team see every
 * e-mail with its state in plain words, filter and page it; nobody else opens it.
 */
class EmailLogIT extends ReleaseScenarioSupport {

    @Autowired
    JdbcTemplate jdbc;

    @AfterEach
    void clearList() {
        SystemAdmins.replace(List.of());
    }

    private void email(Long controlId, User to, String template, String status, String error, LocalDateTime next,
                       LocalDateTime at, String text) {
        jdbc.update("INSERT INTO email_outbox (control_id, template_key, recipient_user_id, recipient_email, subject,"
                        + " body_html, body_text, importance, dedupe_key, status, attempts, last_error, provider, created_at,"
                        + " sent_at, next_attempt_at) VALUES (?, ?, ?, ?, 's', '<p>b</p>', ?, 'NORMAL', ?, ?, ?, ?, 'smtp', ?, ?, ?)",
                controlId, template, to.getId(), to.getMail(), text, "el-" + RUN + "-" + next() , status,
                "FAILED".equals(status) ? 3 : 1, error, Timestamp.valueOf(at),
                "SENT".equals(status) ? Timestamp.valueOf(at.plusMinutes(1)) : null,
                next == null ? null : Timestamp.valueOf(next));
    }

    @Test
    void soqmAndSystemAdmin_seeEveryEmail_inPlainWords_andFilterIt_nobodyElseOpensThePage() throws Exception {
        User soqm = soqm("el-soqm");
        User anna = myEdit("el-anna");
        User ivan = myEdit("el-ivan");
        Control control = control("HR-EL", "COMPLETED", people().facilitator(anna).owner(ivan).soqm(soqm));
        LocalDateTime now = LocalDateTime.now().withNano(0);
        email(control.getId(), anna, "T12", "SENT", null, null, now.minusMinutes(5), "Dear Anna, Control done.");
        email(control.getId(), ivan, "T12", "FAILED", "SendFailedException: 550 Invalid Addresses", null,
                now.minusMinutes(4), null);
        email(control.getId(), anna, "T3", "FAILED", "MailConnectException: Couldn't connect", now.plusMinutes(5),
                now.minusMinutes(3), "Reminder text");
        email(control.getId(), ivan, "T7", "SKIPPED_INACTIVE", "the person is inactive or deleted", null,
                now.minusMinutes(2), null);
        String code = control.getControlId();
        MockHttpSession session = login(soqm);

        MvcResult page = call(session, get("/admin/emails").param("q", code));
        assertThat(page.getResponse().getStatus()).isEqualTo(200);
        assertThat(page.getModelAndView().getViewName()).isEqualTo("admin-emails");
        String html = body(page);
        assertThat(html).contains("aria-current=\"page\"", ">E-mails")
                .contains("Control completed", "Reminder", "Returned to Facilitator")
                .contains(anna.getMail(), ivan.getMail(), anna.getDisplayName())
                .contains("Accepted by the mail server",
                        "the mail server did not accept the address; tried 3 times, no more tries",
                        "Try 3 failed (the mail server could not be reached), next try at",
                        "The person is inactive or deleted")
                // The text behind View while it is kept, a dash after the 30 days
                .contains("Dear Anna, Control done.", "js-mail-view", "title=\"The text is kept 30 days\"")
                .contains("href=\"/view-control/" + control.getId() + "\"")
                // The English calendar of the app, not the browser's date field; no note under the table
                .contains("data-date-picker", "/js/date-picker.js", "/js/time-ago.js", "data-now=\"", "data-time=\"").doesNotContain("type=\"date\"", "means the mail server");
        assertThat(countOf(html, "class=\"email-row\"")).isEqualTo(4);
        // The counters of the period: one of each
        for (String label : List.of("Sent", "Waiting", "Failed", "Not sent")) {
            assertThat(html).as(label).containsPattern(">" + label + "</span>\\s*<span class=\"ap-card-count\">1</span>");
        }

        // Filters: a status, an event, a person
        assertThat(countOf(body(call(session, get("/admin/emails").param("q", code).param("status", "FAILED"))),
                "class=\"email-row\"")).isEqualTo(1);
        assertThat(countOf(body(call(session, get("/admin/emails").param("q", code).param("event", "T12"))),
                "class=\"email-row\"")).isEqualTo(2);
        assertThat(countOf(body(call(session, get("/admin/emails").param("q", ivan.getDisplayName()))),
                "class=\"email-row\"")).isEqualTo(2);
        // A period before them: none
        assertThat(body(call(session, get("/admin/emails").param("q", code)
                .param("from", "2020-01-01").param("to", "2020-01-31")))).contains("No e-mails in this period");

        // System Admin Team sees it too; a User, Read Only and KDN are sent home
        User admin = person("el-sysadmin", AccessLevel.SOQM, AccessScope.ALL);
        SystemAdmins.replace(List.of(admin.getMail()));
        assertThat(call(login(admin), get("/admin/emails").param("q", code)).getModelAndView().getViewName())
                .isEqualTo("admin-emails");
        for (User user : List.of(anna, myRead("el-ro"), allEdit("el-all"), kdn("el-kdn"))) {
            MvcResult refused = call(login(user), get("/admin/emails"));
            assertThat(refused.getResponse().getRedirectedUrl()).as(user.getMail()).isEqualTo("/");
        }
    }

    @Test
    void manyEmails_comeInPagesOfFifty() throws Exception {
        User soqm = soqm("el2-soqm");
        User anna = myEdit("el2-anna");
        Control control = control("HR-EL2", "IN_PROGRESS", people().facilitator(anna).soqm(soqm));
        LocalDateTime now = LocalDateTime.now().withNano(0);
        for (int i = 0; i < 55; i++) {
            email(control.getId(), anna, "T3", "SENT", null, null, now.minusMinutes(i), null);
        }
        MockHttpSession session = login(soqm);
        String first = body(call(session, get("/admin/emails").param("q", control.getControlId())));
        assertThat(countOf(first, "class=\"email-row\"")).isEqualTo(50);
        assertThat(first).contains("Showing 1-50 of 55", "1 / 2");
        String second = body(call(session, get("/admin/emails").param("q", control.getControlId()).param("page", "2")));
        assertThat(countOf(second, "class=\"email-row\"")).isEqualTo(5);
        assertThat(second).contains("Showing 51-55 of 55");
    }

    private static int countOf(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) {
            count++;
        }
        return count;
    }
}
