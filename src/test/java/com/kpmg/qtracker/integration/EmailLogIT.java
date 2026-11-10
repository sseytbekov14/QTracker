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
                        + " sent_at, next_attempt_at) VALUES (?, ?, ?, ?, 'Control completed', ?, ?, 'NORMAL', ?, ?, ?, ?, 'smtp', ?, ?, ?)",
                // the text kept: an HTML and a plain part; null: both cleared after the 30 days (DataRetention)
                controlId, template, to.getId(), to.getMail(), text == null ? "" : "<p>" + text + "</p>", text,
                "el-" + RUN + "-" + next() , status,
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
                // View while the text is kept (loaded when opened, not in the page), a dash after the 30 days
                .contains("js-mail-view", "data-subject=\"Control completed\"", "title=\"The text is kept 30 days\"")
                .doesNotContain("Dear Anna, Control done.")
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

    @Test
    void view_showsTheEmailAsSent_itsHtmlWithThePictures_andItsPlainText_toThoseWhoSeeThePage() throws Exception {
        User soqm = soqm("el3-soqm");
        User anna = myEdit("el3-anna");
        Control control = control("HR-EL3", "COMPLETED", people().facilitator(anna).soqm(soqm));
        LocalDateTime at = LocalDateTime.now().withNano(0);
        String html = "<!DOCTYPE html><html><body><img src=\"cid:qtracker-logo\" alt=\"KPMG QTracker\">"
                + "<!--[if mso]><img src=\"cid:qtracker-button\"><![endif]--><p>Dear Anna, &lt;b&gt;done&lt;/b&gt;.</p></body></html>";
        jdbc.update("INSERT INTO email_outbox (control_id, template_key, recipient_user_id, recipient_email, subject, body_html,"
                        + " body_text, importance, dedupe_key, status, attempts, provider, created_at, sent_at)"
                        + " VALUES (?, 'T12', ?, ?, 'Control completed', ?, 'Dear Anna, <b>done</b>.', 'NORMAL', ?, 'SENT', 1,"
                        + " 'smtp', ?, ?)",
                control.getId(), anna.getId(), anna.getMail(), html, "el3-" + RUN, Timestamp.valueOf(at),
                Timestamp.valueOf(at.plusMinutes(1)));
        long id = jdbc.queryForObject("SELECT id FROM email_outbox WHERE dedupe_key = ?", Long.class, "el3-" + RUN);
        MockHttpSession session = login(soqm);

        assertThat(body(call(session, get("/admin/emails").param("q", control.getControlId()))))
                .contains("data-id=\"" + id + "\"", "/admin/emails/' + encodeURIComponent(currentId) + '/html'");

        // The HTML as sent, the pictures it carries put in, on a page that runs and loads nothing
        MvcResult shown = call(session, get("/admin/emails/{id}/html", id));
        assertThat(shown.getResponse().getStatus()).isEqualTo(200);
        assertThat(shown.getResponse().getContentType()).startsWith("text/html");
        assertThat(shown.getResponse().getHeader("Content-Security-Policy"))
                .isEqualTo("default-src 'none'; img-src data:; style-src 'unsafe-inline'; frame-ancestors 'self';"
                        + " base-uri 'none'; form-action 'none'");
        assertThat(shown.getResponse().getHeader("Cache-Control")).isEqualTo("no-store");
        String page = shown.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(page).contains("<p>Dear Anna, &lt;b&gt;done&lt;/b&gt;.</p>", "<!--[if mso]>")
                .contains("src=\"data:image/png;base64,iVBOR").doesNotContain("cid:");
        // The plain-text part, as text
        MvcResult text = call(session, get("/admin/emails/{id}/text", id));
        assertThat(text.getResponse().getContentType()).startsWith("text/plain");
        assertThat(text.getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .isEqualTo("Dear Anna, <b>done</b>.");

        // Only the plain text kept: shown as text in the frame
        jdbc.update("UPDATE email_outbox SET body_html = '' WHERE id = ?", id);
        assertThat(call(session, get("/admin/emails/{id}/html", id)).getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8))
                .contains("<pre", "Dear Anna, &lt;b&gt;done&lt;/b&gt;.");
        // Cleared after the 30 days, or no such e-mail: not found
        jdbc.update("UPDATE email_outbox SET body_text = NULL WHERE id = ?", id);
        assertThat(call(session, get("/admin/emails/{id}/html", id)).getResponse().getStatus()).isEqualTo(404);
        assertThat(call(session, get("/admin/emails/{id}/text", id)).getResponse().getStatus()).isEqualTo(404);
        assertThat(call(session, get("/admin/emails/{id}/html", 99_999_999L)).getResponse().getStatus()).isEqualTo(404);

        // Nobody else: a User is refused, without a session it asks to sign in
        jdbc.update("UPDATE email_outbox SET body_html = ?, body_text = 'x' WHERE id = ?", html, id);
        for (User user : List.of(anna, myRead("el3-ro"), kdn("el3-kdn"))) {
            assertThat(call(login(user), get("/admin/emails/{id}/html", id)).getResponse().getStatus()).as(user.getMail()).isEqualTo(403);
            assertThat(call(login(user), get("/admin/emails/{id}/text", id)).getResponse().getStatus()).as(user.getMail()).isEqualTo(403);
        }
        assertThat(call(null, get("/admin/emails/{id}/html", id)).getResponse().getStatus()).isNotEqualTo(200);
        // System Admin Team sees it
        User admin = person("el3-sysadmin", AccessLevel.SOQM, AccessScope.ALL);
        SystemAdmins.replace(List.of(admin.getMail()));
        assertThat(call(login(admin), get("/admin/emails/{id}/html", id)).getResponse().getStatus()).isEqualTo(200);
    }

    private static int countOf(String text, String part) {
        int count = 0;
        for (int at = text.indexOf(part); at >= 0; at = text.indexOf(part, at + 1)) {
            count++;
        }
        return count;
    }
}
