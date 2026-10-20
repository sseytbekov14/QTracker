package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.EmailOutbox;
import com.kpmg.qtracker.entity.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.MvcResult;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * The acceptance criteria AC-1..AC-10 of TZ_SoQM_Notifications section 8, with the templates and events of
 * NOTIFICATIONS_v5 (where they differ, v5 holds: AC-1 is S1, S2, E1, E3, E6, E8; AC-9 T12 to the four roles). The
 * calendar is November 2026, the clock fixed by the test; the e-mails are the rows of the outbox.
 * Occurrence Thu 05.11.2026, deadline Thu 19.11.2026 unless a test says otherwise.
 */
class NotificationAcceptanceIT extends NotificationScenarioSupport {

    static final LocalDate OCCURRENCE = LocalDate.of(2026, 11, 5);
    static final LocalDate DEADLINE = LocalDate.of(2026, 11, 19);

    record Cast(User fac, User op, User soqm, User po) {
    }

    Cast cast(String tag) {
        return new Cast(myEdit(tag + "-fac"), myEdit(tag + "-op"), soqm(tag + "-soqm"), myEdit(tag + "-po"));
    }

    Control control(String status, Cast cast) throws Exception {
        Control control = control("AC", status, people().facilitator(cast.fac).operator(cast.op).soqm(cast.soqm)
                .owner(cast.po));
        return dated(control, OCCURRENCE, DEADLINE);
    }

    void move(User actor, Control control, String target, String comment, LocalDate day, int hour) throws Exception {
        CLOCK.set(day.atTime(hour, 0));
        MockHttpSession session = login(actor);
        MvcResult result = call(session, post("/api/workflow/move").param("controlId", String.valueOf(control.getId()))
                .param("targetStatus", target).param("comments", comment));
        assertThat(result.getResponse().getStatus()).as(body(result)).isEqualTo(200);
    }

    void initiate(User soqm, Control control, LocalDate day, int hour) throws Exception {
        CLOCK.set(day.atTime(hour, 0));
        MvcResult result = call(login(soqm), post("/api/performance/initiate")
                .param("controlId", String.valueOf(control.getId())));
        assertThat(result.getResponse().getStatus()).as(body(result)).isIn(200, 302);
        assertThat(reload(control).getPerformanceStatus()).isEqualTo("IN_PROGRESS");
    }

    List<String> templates(Control control) {
        return mails(control).stream().map(EmailOutbox::getTemplateKey).distinct().toList();
    }

    @Test
    @DisplayName("AC-1: the route without returns or overdue: S1, S2, E1, E3, E6, E8")
    void AC1_theRouteWithoutReturnsOrOverdue_givesSixEmails() throws Exception {
        Cast cast = cast("ac1");
        Control control = control("DRAFT", cast);

        initiate(cast.soqm, control, LocalDate.of(2026, 11, 2), 11);
        runEveryDay(LocalDate.of(2026, 11, 2), OCCURRENCE);
        move(cast.fac, control, "REVIEW", "Done", OCCURRENCE, 11);
        move(cast.op, control, "SOQM_HEAD_REVIEW", "Checked", LocalDate.of(2026, 11, 6), 10);
        move(cast.soqm, control, "PROCESS_OWNER_REVIEW", "Fine", LocalDate.of(2026, 11, 6), 15);
        runAt(LocalDate.of(2026, 11, 9));
        move(cast.po, control, "COMPLETED", "Approved", LocalDate.of(2026, 11, 9), 12);
        runEveryDay(LocalDate.of(2026, 11, 10), LocalDate.of(2026, 11, 30));

        // S1 T1, S2 T2, E1 T6, E3 T8, E6 T10, E8 T12: six e-mails, nothing else
        assertThat(templates(control)).containsExactly("T1", "T2", "T6", "T8", "T10", "T12");
        assertThat(sent(control)).containsExactlyInAnyOrder(
                line("T1", cast.fac, LocalDate.of(2026, 11, 2)), line("T1", cast.op, LocalDate.of(2026, 11, 2)),
                line("T1", cast.po, LocalDate.of(2026, 11, 2)),
                line("T2", cast.fac, OCCURRENCE), line("T2", cast.op, OCCURRENCE), line("T2", cast.po, OCCURRENCE),
                line("T6", cast.op, OCCURRENCE),
                line("T8", cast.soqm, LocalDate.of(2026, 11, 6)),
                line("T10", cast.po, LocalDate.of(2026, 11, 6)),
                line("T12", cast.fac, LocalDate.of(2026, 11, 9)), line("T12", cast.op, LocalDate.of(2026, 11, 9)),
                line("T12", cast.soqm, LocalDate.of(2026, 11, 9)), line("T12", cast.po, LocalDate.of(2026, 11, 9)));
    }

    @Test
    @DisplayName("AC-2: the Facilitator has not submitted by working day 3: T3 at 09:30")
    void AC2_notSubmittedByWorkingDay3_facilitatorGetsT3At0930() throws Exception {
        Cast cast = cast("ac2");
        Control control = control("IN_PROGRESS", cast);

        runEveryDay(OCCURRENCE, LocalDate.of(2026, 11, 10));

        EmailOutbox reminder = mails(control).stream().filter(m -> m.getTemplateKey().equals("T3")).findFirst().orElseThrow();
        assertThat(reminder.getRecipientEmail()).isEqualTo(cast.fac.getMail());
        assertThat(reminder.getCreatedAt()).isEqualTo(LocalDate.of(2026, 11, 10).atTime(LocalTime.of(9, 30)));
        assertThat(reminder.getSubject()).isEqualTo("Control Reminder");
        assertThat(reminder.getBodyHtml()).contains("Dear Facilitator,", "is pending your action",
                "The deadline of the control is 19-11-2026.");
    }

    @Test
    @DisplayName("AC-3: occurrence on Friday: working day 3 is Wednesday")
    void AC3_occurrenceOnFriday_workingDay3IsWednesday() throws Exception {
        Cast cast = cast("ac3");
        Control control = dated(control("IN_PROGRESS", cast), LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 20));

        runEveryDay(LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 13));

        assertThat(mails(control)).filteredOn(m -> m.getTemplateKey().equals("T3")).first()
                .extracting(m -> m.getCreatedAt().toLocalDate()).isEqualTo(LocalDate.of(2026, 11, 11));
    }

    @Test
    @DisplayName("AC-4: at the Control Operator on working day 6: T3 to the Control Operator only")
    void AC4_atTheOperatorOnWorkingDay6_onlyTheOperatorGetsT3() throws Exception {
        Cast cast = cast("ac4");
        Control control = control("REVIEW", cast);

        runAt(LocalDate.of(2026, 11, 13));

        assertThat(sent(control)).containsExactly(line("T3", cast.op, LocalDate.of(2026, 11, 13)));
    }

    @Test
    @DisplayName("AC-5: at SoQM Admin Team on the deadline day: no reminder")
    void AC5_atSoqmOnTheDeadline_noReminder() throws Exception {
        Cast cast = cast("ac5");
        Control control = control("SOQM_HEAD_REVIEW", cast);

        runEveryDay(LocalDate.of(2026, 11, 6), LocalDate.of(2026, 11, 27));

        assertThat(mails(control)).isEmpty();
    }

    @Test
    @DisplayName("AC-6: overdue at the Process Owner: T5 every working day until the completion")
    void AC6_overdueAtTheProcessOwner_t5EveryWorkingDay_untilCompleted() throws Exception {
        Cast cast = cast("ac6");
        Control control = control("PROCESS_OWNER_REVIEW", cast);

        runEveryDay(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 27));
        move(cast.po, control, "COMPLETED", "Done at last", LocalDate.of(2026, 11, 27), 12);
        runEveryDay(LocalDate.of(2026, 11, 28), LocalDate.of(2026, 12, 4));

        assertThat(mails(control)).filteredOn(m -> m.getTemplateKey().equals("T5"))
                .allSatisfy(mail -> {
                    assertThat(mail.getRecipientEmail()).isEqualTo(cast.po.getMail());
                    assertThat(mail.getImportance()).isEqualTo("HIGH");
                })
                .extracting(m -> m.getCreatedAt().toLocalDate())
                .containsExactly(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 23), LocalDate.of(2026, 11, 24),
                        LocalDate.of(2026, 11, 25), LocalDate.of(2026, 11, 26), LocalDate.of(2026, 11, 27));
    }

    @Test
    @DisplayName("AC-7: the Process Owner returns the control: T11 to the Control Operator, no more reminders to the Process Owner")
    void AC7_processOwnerReturns_operatorGetsT11_ownerNoMoreReminders() throws Exception {
        Cast cast = cast("ac7");
        Control control = control("PROCESS_OWNER_REVIEW", cast);

        runAt(LocalDate.of(2026, 11, 13));
        move(cast.po, control, "REVIEW", "The March evidence is missing", LocalDate.of(2026, 11, 13), 12);
        runEveryDay(LocalDate.of(2026, 11, 16), LocalDate.of(2026, 11, 20));

        assertThat(sent(control)).containsExactly(
                line("T3", cast.po, LocalDate.of(2026, 11, 13)),
                line("T11", cast.op, LocalDate.of(2026, 11, 13)),
                line("T4", cast.op, DEADLINE),
                line("T5", cast.op, LocalDate.of(2026, 11, 20)));
        assertThat(inApp(control, cast.op)).anySatisfy(notice -> assertThat(notice.getMessage())
                .contains("has been reviewed by the Process Owner and requires some adjustments")
                .endsWith("Return Comment:\nThe March evidence is missing"));
    }

    @Test
    @DisplayName("AC-8: Facilitator and Control Operator one person, S1: one e-mail")
    void AC8_facilitatorAndOperatorOnePerson_s1IsOneEmail() throws Exception {
        User both = myEdit("ac8-both");
        User soqm = soqm("ac8-soqm");
        User owner = myEdit("ac8-po");
        Control control = dated(control("AC8", "DRAFT", people().facilitator(both).operator(both).soqm(soqm).owner(owner)),
                OCCURRENCE, DEADLINE);

        initiate(soqm, control, LocalDate.of(2026, 11, 2), 11);

        assertThat(sent(control)).containsExactlyInAnyOrder(line("T1", both, LocalDate.of(2026, 11, 2)),
                line("T1", owner, LocalDate.of(2026, 11, 2)));
        assertThat(inApp(control, both)).hasSize(1);
    }

    @Test
    @DisplayName("AC-9: the control is completed: T12 to the four roles, no more reminders")
    void AC9_completed_t12ToTheFourRoles_noMoreReminders() throws Exception {
        Cast cast = cast("ac9");
        Control control = control("PROCESS_OWNER_REVIEW", cast);

        move(cast.po, control, "COMPLETED", "Approved", LocalDate.of(2026, 11, 12), 12);
        runEveryDay(LocalDate.of(2026, 11, 13), LocalDate.of(2026, 11, 30));

        assertThat(sent(control)).containsExactlyInAnyOrder(
                line("T12", cast.fac, LocalDate.of(2026, 11, 12)), line("T12", cast.op, LocalDate.of(2026, 11, 12)),
                line("T12", cast.soqm, LocalDate.of(2026, 11, 12)), line("T12", cast.po, LocalDate.of(2026, 11, 12)));
        assertThat(mails(control).get(0).getBodyHtml())
                .contains("Dear Facilitator, Control Operator, SoQM Admin Team and Process Owner,",
                        "has been successfully completed in the system.", "Go to Control");
    }

    @Test
    @DisplayName("AC-10: the deadline is today: T4, \"deadline ... is today\", High importance")
    void AC10_deadlineToday_t4WithHighImportance() throws Exception {
        Cast cast = cast("ac10");
        Control control = control("IN_PROGRESS", cast);

        runAt(DEADLINE);

        assertThat(mails(control)).singleElement().satisfies(mail -> {
            assertThat(mail.getTemplateKey()).isEqualTo("T4");
            assertThat(mail.getRecipientEmail()).isEqualTo(cast.fac.getMail());
            assertThat(mail.getImportance()).isEqualTo("HIGH");
            assertThat(mail.getSubject()).isEqualTo("Control Deadline Today");
            assertThat(mail.getBodyHtml()).contains("the deadline for the following control is today, 19-11-2026:");
        });
    }
}
