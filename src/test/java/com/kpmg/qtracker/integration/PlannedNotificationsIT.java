package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.EmailOutbox;
import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.entity.User;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The planned notifications S2..S6 on the calendar of November 2026 (NOTIFICATIONS_v5 4.1, 6.1): the occurrence day to
 * everyone, the reminders on working days 3 and 6, the deadline day and every working day after it to the holder of
 * the step only, never at a weekend, never before 09:30, never twice; the frequency changes nothing.
 * Occurrence Thu 05.11.2026, deadline Thu 19.11.2026 (10 working days).
 */
class PlannedNotificationsIT extends NotificationScenarioSupport {

    static final LocalDate OCCURRENCE = LocalDate.of(2026, 11, 5);
    static final LocalDate DEADLINE = LocalDate.of(2026, 11, 19);

    record Cast(User fac, User op, User soqm, User po) {
    }

    Cast cast(String tag) {
        return new Cast(myEdit(tag + "-fac"), myEdit(tag + "-op"), soqm(tag + "-soqm"), myEdit(tag + "-po"));
    }

    Control control(String id, String status, Cast cast, String frequency) throws Exception {
        Control control = control(id, status, people().facilitator(cast.fac).operator(cast.op).soqm(cast.soqm)
                .owner(cast.po), c -> c.setControlFrequency(frequency));
        return dated(control, OCCURRENCE, DEADLINE);
    }

    static List<String> calendar(Cast cast, User holder) {
        return List.of(
                line("T2", cast.fac, OCCURRENCE), line("T2", cast.op, OCCURRENCE), line("T2", cast.po, OCCURRENCE),
                line("T3", holder, LocalDate.of(2026, 11, 10)),
                line("T3", holder, LocalDate.of(2026, 11, 13)),
                line("T4", holder, DEADLINE),
                line("T5", holder, LocalDate.of(2026, 11, 20)),
                line("T5", holder, LocalDate.of(2026, 11, 23)),
                line("T5", holder, LocalDate.of(2026, 11, 24)),
                line("T5", holder, LocalDate.of(2026, 11, 25)));
    }

    /** Every frequency gives the same e-mails on the same days (decision 9). */
    @ParameterizedTest
    @ValueSource(strings = {"Monthly", "Quarterly", "Ad-hoc", "Recurring", "Annual", "Semi Annual"})
    void everyFrequency_theSameCalendar(String frequency) throws Exception {
        Cast cast = cast("pn-" + frequency.substring(0, 3).toLowerCase());
        Control control = control("PN-FREQ", "IN_PROGRESS", cast, frequency);

        runEveryDay(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 25));

        assertThat(sent(control)).containsExactlyElementsOf(calendar(cast, cast.fac));
        assertThat(mails(control)).allSatisfy(mail -> {
            assertThat(mail.getImportance()).isEqualTo(mail.getTemplateKey().matches("T4|T5") ? "HIGH" : "NORMAL");
            assertThat(mail.getSubject()).doesNotContainIgnoringCase(frequency);
            assertThat(mail.getBodyHtml()).doesNotContainIgnoringCase(frequency.toLowerCase());
            assertThat(mail.getCreatedAt().toLocalTime()).isEqualTo(java.time.LocalTime.of(9, 30));
        });
        assertThat(mails(control)).filteredOn(m -> m.getTemplateKey().equals("T3")).allSatisfy(mail ->
                assertThat(mail.getBodyHtml()).startsWith("<div").contains("Dear " + cast.fac.getDisplayName() + ",<br>"));
    }

    @Test
    void theHolderOfTheStep_getsTheReminders_soqmReviewNobody() throws Exception {
        Cast review = cast("pn-rev");
        Cast soqmReview = cast("pn-sq");
        Cast ownerReview = cast("pn-por");
        Control atOperator = control("PN-REVIEW", "REVIEW", review, "Monthly");
        Control atSoqm = control("PN-SOQM", "SOQM_HEAD_REVIEW", soqmReview, "Monthly");
        Control atOwner = control("PN-PO", "PROCESS_OWNER_REVIEW", ownerReview, "Monthly");

        runEveryDay(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 25));

        assertThat(sent(atOperator)).containsExactlyElementsOf(calendar(review, review.op));
        assertThat(sent(atOwner)).containsExactlyElementsOf(calendar(ownerReview, ownerReview.po));
        // SoQM review: the occurrence day goes to everyone, no reminder to anybody (AC-5)
        assertThat(sent(atSoqm)).containsExactlyElementsOf(calendar(soqmReview, soqmReview.soqm).subList(0, 3));
        assertThat(mails(atOperator)).filteredOn(m -> m.getTemplateKey().equals("T5"))
                .allSatisfy(mail -> assertThat(mail.getBodyHtml()).contains("Dear " + review.op.getDisplayName() + ",<br>"));
        assertThat(mails(atOwner)).filteredOn(m -> m.getTemplateKey().equals("T4"))
                .allSatisfy(mail -> assertThat(mail.getBodyHtml()).contains("Dear " + ownerReview.po.getDisplayName() + ",<br>"));
    }

    @Test
    void aStatusChange_movesTheRemindersToTheNewHolder() throws Exception {
        Cast cast = cast("pn-move");
        Control control = control("PN-MOVE", "IN_PROGRESS", cast, "Monthly");

        runEveryDay(LocalDate.of(2026, 11, 5), LocalDate.of(2026, 11, 11));
        status(control, "REVIEW");
        runEveryDay(LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 19));
        status(control, "PROCESS_OWNER_REVIEW");
        runEveryDay(LocalDate.of(2026, 11, 20), LocalDate.of(2026, 11, 20));

        assertThat(sent(control)).containsExactly(
                line("T2", cast.fac, OCCURRENCE), line("T2", cast.op, OCCURRENCE), line("T2", cast.po, OCCURRENCE),
                line("T3", cast.fac, LocalDate.of(2026, 11, 10)),
                line("T3", cast.op, LocalDate.of(2026, 11, 13)),
                line("T4", cast.op, DEADLINE),
                line("T5", cast.po, LocalDate.of(2026, 11, 20)));
    }

    @Test
    void notAtTheWeekend_notBefore0930_neverTwice() throws Exception {
        Cast cast = cast("pn-time");
        Control control = control("PN-TIME", "IN_PROGRESS", cast, "Monthly");

        assertThat(runAt(at(OCCURRENCE, 9, 29))).isZero();
        assertThat(mails(control)).isEmpty();
        assertThat(runAt(at(OCCURRENCE, 9, 30))).isEqualTo(3);
        assertThat(runAt(at(OCCURRENCE, 9, 35))).isZero();
        assertThat(runAt(at(OCCURRENCE, 17, 0))).isZero();
        // After the deadline, Saturday and Sunday make nothing
        assertThat(runAt(LocalDate.of(2026, 11, 21))).isZero();
        assertThat(runAt(LocalDate.of(2026, 11, 22))).isZero();
        assertThat(sent(control)).hasSize(3);
        // One in-app notification per e-mail
        assertThat(inApp(control, cast.fac)).singleElement().extracting(Notification::getTitle)
                .isEqualTo("The activation of the SoQM control");
    }

    @Test
    void aRestartAfter0930_makesUpToday_butNotThePastDays() throws Exception {
        Cast cast = cast("pn-restart");
        Control control = control("PN-RESTART", "IN_PROGRESS", cast, "Monthly");
        runAt(OCCURRENCE);

        // Down from 09:00 to 10:15 on working day 3: the run at 10:15 makes the reminder of today
        runAt(at(LocalDate.of(2026, 11, 10), 10, 15));
        // Down the whole of working day 6 (13.11): not made up on Monday
        runAt(LocalDate.of(2026, 11, 16));

        assertThat(sent(control)).containsExactly(
                line("T2", cast.fac, OCCURRENCE), line("T2", cast.op, OCCURRENCE), line("T2", cast.po, OCCURRENCE),
                line("T3", cast.fac, LocalDate.of(2026, 11, 10)));
    }

    @Test
    void occurrenceOnFriday_workingDay3IsWednesday() throws Exception {
        Cast cast = cast("pn-fri");
        Control control = control("PN-FRI", "IN_PROGRESS", cast, "Monthly");
        LocalDate friday = LocalDate.of(2026, 11, 6);
        dated(control, friday, LocalDate.of(2026, 11, 20));

        runEveryDay(friday, LocalDate.of(2026, 11, 12));

        assertThat(sent(control)).containsExactly(
                line("T2", cast.fac, friday), line("T2", cast.op, friday), line("T2", cast.po, friday),
                line("T3", cast.fac, LocalDate.of(2026, 11, 11)));
    }

    @Test
    void returnedFromCompleted_orWithoutDates_orInDraft_getsNothingPlanned() throws Exception {
        Control reopened = control("PN-REOPEN", "REVIEW", cast("pn-reop"), "Monthly");
        jdbc.update("UPDATE controls SET reopened_at = ? WHERE id = ?", LocalDateTime.of(2026, 11, 4, 12, 0), reopened.getId());
        Control noDates = control("PN-NODATE", "IN_PROGRESS", cast("pn-nodate"), "Quarterly");
        jdbc.update("UPDATE controls SET control_operation_date = NULL WHERE id = ?", noDates.getId());
        Control draft = control("PN-DRAFT", "DRAFT", cast("pn-draft"), "Monthly");

        runEveryDay(LocalDate.of(2026, 11, 2), LocalDate.of(2026, 11, 25));

        assertThat(mails(reopened)).isEmpty();
        assertThat(mails(noDates)).isEmpty();
        assertThat(mails(draft)).isEmpty();
    }

    @Test
    void aChangedDate_doesNotRepeatWhatWasSent() throws Exception {
        Cast cast = cast("pn-redate");
        Control control = control("PN-REDATE", "IN_PROGRESS", cast, "Monthly");
        runEveryDay(OCCURRENCE, LocalDate.of(2026, 11, 10));

        // The occurrence moves a week later: day 0 and day 3 come again by the dates, but were sent already
        dated(control, LocalDate.of(2026, 11, 12), LocalDate.of(2026, 11, 26));
        runEveryDay(LocalDate.of(2026, 11, 11), LocalDate.of(2026, 11, 17));

        assertThat(sent(control)).containsExactly(
                line("T2", cast.fac, OCCURRENCE), line("T2", cast.op, OCCURRENCE), line("T2", cast.po, OCCURRENCE),
                line("T3", cast.fac, LocalDate.of(2026, 11, 10)));
    }

    @Test
    void twoInstancesAtOnce_makeEveryEmailOnce() throws Exception {
        Cast cast = cast("pn-race");
        Control control = control("PN-RACE", "IN_PROGRESS", cast, "Monthly");
        CLOCK.set(at(OCCURRENCE, 9, 30));

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (int i = 0; i < 2; i++) {
            pool.submit(() -> {
                start.await();
                return scheduler.runOnce();
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        assertThat(sent(control)).containsExactly(
                line("T2", cast.fac, OCCURRENCE), line("T2", cast.op, OCCURRENCE), line("T2", cast.po, OCCURRENCE));
        assertThat(inApp(control, cast.fac)).hasSize(1);
        assertThat(mails(control)).extracting(EmailOutbox::getStatus).containsOnly(EmailOutbox.PENDING);
    }
}
