package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.EmailOutbox;
import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.EmailOutboxRepository;
import com.kpmg.qtracker.service.notification.PlannedNotificationScheduler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.convention.TestBean;

import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.List;

/**
 * The notifications on a simulated calendar: the Clock bean is a {@link MutableClock} the test sets, the planned
 * notifications run when the test calls them (09:30 by default), e-mail is off (the outbox rows are what was sent).
 * Its own H2 database, shared by the classes of this support.
 */
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:notifications-v5-it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "app.base-url=https://qtracker.example.test"})
abstract class NotificationScenarioSupport extends ReleaseScenarioSupport {

    static final MutableClock CLOCK = new MutableClock(LocalDateTime.of(2026, 11, 2, 8, 0));

    @TestBean(name = "clock", methodName = "simClock")
    Clock clock;

    static Clock simClock() {
        return CLOCK;
    }

    @Autowired
    PlannedNotificationScheduler scheduler;
    @Autowired
    EmailOutboxRepository outbox;
    @Autowired
    JdbcTemplate jdbc;

    /** The controls of earlier tests leave the runs of this one alone. */
    @org.junit.jupiter.api.BeforeEach
    void earlierControlsDone() {
        jdbc.update("UPDATE controls SET performance_status = 'COMPLETED'");
    }

    /** The planned notifications at 09:30 of that day. */
    int runAt(LocalDate day) {
        return runAt(day.atTime(9, 30));
    }

    int runAt(LocalDateTime at) {
        CLOCK.set(at);
        return scheduler.runOnce();
    }

    /** Runs at 09:30 on every day from..to (weekends included: the scheduler makes nothing then). */
    void runEveryDay(LocalDate from, LocalDate to) {
        for (LocalDate day = from; !day.isAfter(to); day = day.plusDays(1)) {
            runAt(day);
        }
    }

    /** Sets Control Operation Date and Deadline as stored (the deadline is never calculated by the notifications). */
    Control dated(Control control, LocalDate occurrence, LocalDate deadline) {
        ControlAssignment assignment = assignmentRepository.findByControlId(control.getId()).orElseThrow();
        assignment.setControlOperationDate(occurrence);
        assignment.setControlOperationDeadline(deadline);
        assignmentRepository.save(assignment);
        return reload(control);
    }

    Control status(Control control, String status) {
        Control stored = reload(control);
        stored.setPerformanceStatus(status);
        return controlRepository.save(stored);
    }

    List<EmailOutbox> mails(Control control) {
        return outbox.findByControlIdOrderByIdAsc(control.getId());
    }

    /** "T3 a@x 2026-11-10" per e-mail of the control, in the order made. */
    List<String> sent(Control control) {
        return mails(control).stream()
                .map(mail -> mail.getTemplateKey() + " " + mail.getRecipientEmail() + " " + mail.getCreatedAt().toLocalDate())
                .toList();
    }

    static String line(String template, User to, LocalDate day) {
        return template + " " + to.getMail() + " " + day;
    }

    List<Notification> inApp(Control control, User user) {
        return notificationRepository.findByControlIdOrderByCreatedAtDesc(control.getId()).stream()
                .filter(notice -> notice.getUserId().equals(user.getId())).toList();
    }

    static LocalDateTime at(LocalDate day, int hour, int minute) {
        return day.atTime(LocalTime.of(hour, minute));
    }
}
