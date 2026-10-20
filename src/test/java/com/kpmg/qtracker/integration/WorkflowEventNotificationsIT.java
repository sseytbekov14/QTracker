package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.EmailOutbox;
import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.entity.WorkflowHistory;
import com.kpmg.qtracker.service.ControlAssignmentService;
import com.kpmg.qtracker.service.notification.NotificationValues;
import com.kpmg.qtracker.service.notification.Template;
import com.kpmg.qtracker.service.notification.TemplateRenderer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The notifications of the workflow (NOTIFICATIONS_v5 4.2, decisions 4 and 5): every change of status, made by the
 * role itself or by SoQM Admin Team on behalf of it, gives the same template, the same people and the same text; the
 * text never says "on behalf"; {RETURNED_BY} is the role of the step; nothing of type ON_BEHALF; the history keeps who
 * made the move and for whom. One e-mail per person and move, the comment of a return in-app only.
 */
class WorkflowEventNotificationsIT extends NotificationScenarioSupport {

    @Autowired
    TemplateRenderer renderer;
    @Autowired
    NotificationValues values;
    @Autowired
    ControlAssignmentService assignments;

    record Cast(User fac, User op, User soqm, User po, User otherSoqm) {

        User of(String role) {
            return switch (role) {
                case "F" -> fac;
                case "CO" -> op;
                case "SOQM" -> soqm;
                case "PO" -> po;
                default -> otherSoqm;
            };
        }
    }

    Cast cast(String tag) {
        return new Cast(myEdit(tag + "-fac"), myEdit(tag + "-op"), soqm(tag + "-soqm"), myEdit(tag + "-po"),
                soqm(tag + "-soqm2"));
    }

    Control control(String status, Cast cast) throws Exception {
        Control control = control("EV", status, people().facilitator(cast.fac).operator(cast.op).soqm(cast.soqm)
                .owner(cast.po));
        return dated(control, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 19));
    }

    MvcResult move(User actor, Control control, String target, String comment) throws Exception {
        MockHttpSession session = login(actor);
        return call(session, post("/api/workflow/move").param("controlId", String.valueOf(control.getId()))
                .param("targetStatus", target).param("comments", comment));
    }

    /** The text the template gives for this control, as the role itself would get it. */
    String expectedHtml(Control control, Template template, String returnedBy) {
        Map<String, String> placeholders = values.of(reload(control), assignments.getAssignmentByControlId(control.getId()));
        if (returnedBy != null) {
            placeholders.put(NotificationValues.RETURNED_BY, returnedBy);
        }
        return renderer.render(template, placeholders).rendered().html();
    }

    /**
     * actor: F, CO, PO, SOQM (in the field SoQM Team / Delegate) or OTHER (a SoQM user not in it, acting for the role
     * of the step); people: who gets it.
     */
    @ParameterizedTest(name = "{0} -> {1} by {2}: {3}")
    @CsvSource(nullValues = "-", value = {
            "IN_PROGRESS,          REVIEW,               F,     T6,  CO,          -",
            "IN_PROGRESS,          REVIEW,               OTHER, T6,  CO,          -",
            "REVIEW,               IN_PROGRESS,          CO,    T7,  F,           Control Operator",
            "REVIEW,               IN_PROGRESS,          OTHER, T7,  F,           Control Operator",
            "REVIEW,               SOQM_HEAD_REVIEW,     CO,    T8,  SOQM,        -",
            "REVIEW,               SOQM_HEAD_REVIEW,     OTHER, T8,  SOQM,        -",
            "SOQM_HEAD_REVIEW,     REVIEW,               SOQM,  T9,  CO,          -",
            "SOQM_HEAD_REVIEW,     REVIEW,               OTHER, T9,  CO,          -",
            "SOQM_HEAD_REVIEW,     IN_PROGRESS,          SOQM,  T7,  F,           SoQM Admin Team",
            "SOQM_HEAD_REVIEW,     PROCESS_OWNER_REVIEW, SOQM,  T10, PO,          -",
            "PROCESS_OWNER_REVIEW, REVIEW,               PO,    T11, CO,          -",
            "PROCESS_OWNER_REVIEW, REVIEW,               OTHER, T11, CO,          -",
            "PROCESS_OWNER_REVIEW, COMPLETED,            PO,    T12, F CO SOQM PO, -",
            "PROCESS_OWNER_REVIEW, COMPLETED,            OTHER, T12, F CO SOQM PO, -",
            "PROCESS_OWNER_REVIEW, IN_PROGRESS,          OTHER, T7,  F,           Process Owner",
            "PROCESS_OWNER_REVIEW, SOQM_HEAD_REVIEW,     OTHER, T8,  SOQM,        -",
            "COMPLETED,            REVIEW,               SOQM,  T9,  CO,          -",
            "COMPLETED,            IN_PROGRESS,          SOQM,  T7,  F,           SoQM Admin Team",
            "COMPLETED,            SOQM_HEAD_REVIEW,     SOQM,  T8,  SOQM,        -",
            "COMPLETED,            PROCESS_OWNER_REVIEW, SOQM,  T10, PO,          -",
    })
    void eventByActor_givesTheSameTemplatePeopleAndText(String from, String to, String actor, String template,
                                                         String people, String returnedBy) throws Exception {
        Cast cast = cast("ev-" + template.toLowerCase(Locale.ROOT));
        Control control = control(from, cast);

        MvcResult result = move(cast.of(actor), control, to, "Comment of the move " + from + " to " + to);
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);

        List<User> expected = new ArrayList<>();
        Arrays.stream(people.split(" ")).forEach(role -> expected.add(cast.of(role)));
        List<EmailOutbox> mails = mails(control);
        assertThat(mails).extracting(EmailOutbox::getRecipientEmail)
                .containsExactlyInAnyOrderElementsOf(expected.stream().map(User::getMail).toList());
        String html = expectedHtml(control, Template.valueOf(template), returnedBy);
        assertThat(mails).allSatisfy(mail -> {
            assertThat(mail.getTemplateKey()).isEqualTo(template);
            assertThat(mail.getSubject()).isEqualTo(Template.valueOf(template).defaultSubject());
            assertThat(mail.getBodyHtml()).isEqualTo(html).doesNotContainIgnoringCase("behalf")
                    .doesNotContain("Comment of the move");
            assertThat(mail.getImportance()).isEqualTo("NORMAL");
        });
        if (returnedBy != null) {
            assertThat(html).contains("has been reviewed by the " + returnedBy + " and requires some adjustments.");
        }
        // In-app: the same subject and text, the comment of a return under it; nothing ON_BEHALF to anyone
        List<Notification> notices = notificationRepository.findByControlIdOrderByCreatedAtDesc(control.getId());
        assertThat(notices).extracting(Notification::getUserId)
                .containsExactlyInAnyOrderElementsOf(expected.stream().map(User::getId).toList());
        assertThat(notices).noneMatch(notice -> "ON_BEHALF".equals(notice.getType()));
        boolean isReturn = order(to) < order(from);
        assertThat(notices).allSatisfy(notice -> {
            assertThat(notice.getTitle()).isEqualTo(Template.valueOf(template).defaultSubject());
            assertThat(notice.getMessage()).doesNotContainIgnoringCase("behalf");
            if (isReturn) {
                assertThat(notice.getMessage()).endsWith("Return Comment:\nComment of the move " + from + " to " + to);
            } else {
                assertThat(notice.getMessage()).doesNotContain("Comment of the move");
            }
        });
        // Who made it and for whom: only in the history (and the audit trail)
        WorkflowHistory history = historyRepository.findByControlIdOrderByCreatedAtDesc(control.getId()).get(0);
        assertThat(history.getPerformedByEmail()).isEqualTo(cast.of(actor).getMail());
        // A SoQM user makes the SoQM step as their own, whether in the field or not
        assertThat(history.isOnBehalf()).isEqualTo("OTHER".equals(actor) && !"SOQM_HEAD_REVIEW".equals(from));
        assertThat(history.getComments()).isEqualTo("Comment of the move " + from + " to " + to);
    }

    static int order(String status) {
        return List.of("IN_PROGRESS", "REVIEW", "SOQM_HEAD_REVIEW", "PROCESS_OWNER_REVIEW", "COMPLETED").indexOf(status);
    }

    @Test
    void initiate_sendsT1_toFacilitatorOperatorAndOwner_once() throws Exception {
        Cast cast = cast("ev-init");
        Control control = control("DRAFT", cast);
        CLOCK.set(at(LocalDate.of(2026, 11, 2), 11, 0));

        MvcResult result = call(login(cast.soqm), post("/api/performance/initiate").param("controlId", String.valueOf(control.getId())));
        assertThat(result.getResponse().getStatus()).as(body(result)).isIn(200, 302);
        assertThat(reload(control).getPerformanceStatus()).isEqualTo("IN_PROGRESS");

        assertThat(sent(control)).containsExactlyInAnyOrder(line("T1", cast.fac, LocalDate.of(2026, 11, 2)),
                line("T1", cast.op, LocalDate.of(2026, 11, 2)), line("T1", cast.po, LocalDate.of(2026, 11, 2)));
        assertThat(mails(control).get(0).getBodyHtml()).contains("The occurrence date is 05-11-2026",
                "before the deadline 19-11-2026", "https://qtracker.example.test/view-control/" + control.getId());
    }

    @Test
    void initiatedOnTheOccurrenceDay_getsNoT2() throws Exception {
        Cast cast = cast("ev-sameday");
        Control control = control("DRAFT", cast);
        LocalDate occurrence = LocalDate.of(2026, 11, 5);
        CLOCK.set(at(occurrence, 8, 15));

        call(login(cast.soqm), post("/api/performance/initiate").param("controlId", String.valueOf(control.getId())));
        runAt(occurrence);

        assertThat(mails(control)).extracting(EmailOutbox::getTemplateKey).containsOnly("T1").hasSize(3);
    }

    @Test
    void onePersonInSeveralRoles_getsOneEmailPerMove_andAnInactiveOrKdnPersonNothing() throws Exception {
        User all = myEdit("ev-all");
        User inactiveOwner = disable(myEdit("ev-off"));
        User soqm = soqm("ev-x-soqm");
        Control control = control("EV-X", "PROCESS_OWNER_REVIEW", people().facilitator(all).operator(all).soqm(soqm)
                .owner(all, inactiveOwner));
        dated(control, LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 19));

        MvcResult result = move(all, control, "COMPLETED", "Done");
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);

        assertThat(mails(control)).extracting(EmailOutbox::getRecipientEmail)
                .containsExactlyInAnyOrder(all.getMail(), soqm.getMail());
        assertThat(inApp(control, all)).hasSize(1);
        assertThat(inApp(control, inactiveOwner)).isEmpty();
    }

    @Test
    void anUnknownSoqmTeamAddress_reachesEveryActiveSoqmUser() throws Exception {
        Cast cast = cast("ev-ghost");
        Control control = control("REVIEW", cast);
        jdbc.update("UPDATE controls SET soqm_team = ? WHERE id = ?", "ghost-" + RUN + "@release.test", control.getId());
        User inactiveSoqm = disable(soqm("ev-ghost-off"));

        MvcResult result = move(cast.op, control, "SOQM_HEAD_REVIEW", "To SoQM");
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);

        assertThat(mails(control)).extracting(EmailOutbox::getRecipientEmail)
                .contains(cast.soqm.getMail(), cast.otherSoqm.getMail())
                .doesNotContain(inactiveSoqm.getMail(), cast.op.getMail());
        assertThat(mails(control)).extracting(EmailOutbox::getTemplateKey).containsOnly("T8");
    }

    @Test
    void aFailedMove_leavesNoNotification() throws Exception {
        Cast cast = cast("ev-fail");
        Control control = control("REVIEW", cast);

        // A return without a comment is refused
        MvcResult refused = move(cast.op, control, "IN_PROGRESS", " ");
        assertThat(refused.getResponse().getStatus()).isEqualTo(400);

        assertThat(mails(control)).isEmpty();
        assertThat(notificationRepository.findByControlIdOrderByCreatedAtDesc(control.getId())).isEmpty();
    }
}
