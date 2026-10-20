package com.kpmg.qtracker.service.notification;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.service.ControlAssignmentService;
import com.kpmg.qtracker.service.WorkflowTransition;
import com.kpmg.qtracker.service.WorkingDaysService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * The planned notifications S2..S6 (NOTIFICATIONS_v5 4.1 and 6.1), one scheduler for every frequency: the frequency
 * plays no part. Runs every 5 minutes on working days from 09:30 Asia/Almaty and makes what today calls for and the
 * outbox does not have yet, so a run after a restart makes up today; past days are not made up.
 * <p>For each control In Progress, Review, SoQM review or Process Owner Review that was not returned from Completed
 * (reopened_at, decision 5), in a transaction of its own (a failure stops that control only):
 * <ul>
 * <li>no Control Operation Date or Deadline: nothing, a warning;</li>
 * <li>occurrence after today: nothing;</li>
 * <li>the occurrence day (the first working day from it): T2 to Facilitator, Control Operator, Process Owner, unless
 * T1 went out for the control today;</li>
 * <li>the holder of the step - In Progress the Facilitator, Review the Control Operator, Process Owner Review the
 * Process Owner, SoQM review nobody: T3 on working days 3 and 6 from the occurrence, else T4 on the deadline day, else
 * T5 on every working day after it (High importance for both).</li>
 * </ul>
 * When the status changes, the old holder gets no more and the new one gets them by the same calendar. Each e-mail
 * is made once: T2, T3 (+3 and +6) and T4 once per control and person, T5 once per day; a changed date makes nothing
 * again. {@link #runOnce()} is public for the tests, which call it with their own Clock.
 */
@Service
@Slf4j
public class PlannedNotificationScheduler {

    public static final ZoneId ALMATY = ZoneId.of("Asia/Almaty");
    public static final LocalTime START = LocalTime.of(9, 30);
    public static final List<String> STATUSES = List.of("IN_PROGRESS", "REVIEW", "SOQM_HEAD_REVIEW", "PROCESS_OWNER_REVIEW");

    private final ControlRepository controlRepository;
    private final ControlAssignmentService assignmentService;
    private final ControlNotifications notifications;
    private final WorkingDaysService workingDays;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate perControl;
    private final Clock clock;
    /** Warnings already written today (a run every 5 minutes would repeat them). */
    private final Set<String> warned = new HashSet<>();
    private LocalDate warnedOn;

    public PlannedNotificationScheduler(ControlRepository controlRepository, ControlAssignmentService assignmentService,
                                        ControlNotifications notifications, WorkingDaysService workingDays,
                                        JdbcTemplate jdbc, PlatformTransactionManager transactionManager, Clock clock) {
        this.controlRepository = controlRepository;
        this.assignmentService = assignmentService;
        this.notifications = notifications;
        this.workingDays = workingDays;
        this.jdbc = jdbc;
        this.perControl = new TransactionTemplate(transactionManager);
        this.perControl.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        this.clock = clock;
    }

    /** One run; returns how many e-mails it queued. Nothing at a weekend or before 09:30. */
    public int runOnce() {
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(ALMATY);
        LocalDate today = now.toLocalDate();
        if (!workingDays.isWorkingDay(today) || now.toLocalTime().isBefore(START)) {
            return 0;
        }
        int queued = 0;
        for (Long id : controlRepository.findIdsForPlannedNotifications(STATUSES)) {
            try {
                Integer made = perControl.execute(status -> notify(id, today));
                queued += made == null ? 0 : made;
            } catch (RuntimeException e) {
                log.error("Planned notifications of control {} failed on {}", id, today, e);
            }
        }
        return queued;
    }

    private int notify(Long id, LocalDate today) {
        Control control = controlRepository.findById(id).orElse(null);
        if (control == null || control.getReopenedAt() != null || control.getPerformanceStatus() == null
                || !STATUSES.contains(control.getPerformanceStatus().trim().toUpperCase(Locale.ROOT))) {
            return 0;
        }
        ControlAssignmentDTO assignment = assignmentService.getAssignmentByControlId(id);
        LocalDate occurrence = assignment.getControlOperationDate();
        LocalDate deadline = assignment.getControlOperationDeadline();
        if (occurrence == null || deadline == null) {
            warn(today, "Control " + control.getControlId() + " (" + control.getControlFrequency()
                    + "): no Control Operation Date or Deadline, no planned notifications");
            return 0;
        }
        if (occurrence.isAfter(today)) {
            return 0;
        }
        int queued = 0;
        String key = control.getId() + ":";
        if (today.equals(firstWorkingDay(occurrence)) && !initiatedOn(control, today)) {
            queued += notifications.send(control, assignment, new ControlNotifications.Notice(Template.T2,
                    List.of(WorkflowTransition.Actor.FACILITATOR, WorkflowTransition.Actor.CONTROL_OPERATOR,
                            WorkflowTransition.Actor.PROCESS_OWNER),
                    null, null, email -> key + "T2:D0:" + email, null), message -> warn(today, message));
        }
        WorkflowTransition.Actor holder = holder(control.getPerformanceStatus());
        if (holder == null) {
            return queued;
        }
        int workingDay = workingDays.workingDaysBetween(occurrence, today);
        ControlNotifications.Notice notice;
        if (workingDay == 3 || workingDay == 6) {
            notice = toHolder(Template.T3, holder, email -> key + "T3:D" + workingDay + ":" + email);
        } else if (today.equals(deadline)) {
            notice = toHolder(Template.T4, holder, email -> key + "T4:DL:" + email);
        } else if (today.isAfter(deadline)) {
            notice = toHolder(Template.T5, holder, email -> key + "T5:" + email + ":" + today);
        } else {
            return queued;
        }
        return queued + notifications.send(control, assignment, notice, message -> warn(today, message));
    }

    private static ControlNotifications.Notice toHolder(Template template, WorkflowTransition.Actor holder,
                                                        java.util.function.Function<String, String> key) {
        return new ControlNotifications.Notice(template, List.of(holder), ControlNotifications.roleName(holder), null,
                key, null);
    }

    /** The holder of the step whose reminders go out; none in SoQM review (SoQM Admin Team gets no reminders). */
    static WorkflowTransition.Actor holder(String status) {
        return switch (status.trim().toUpperCase(Locale.ROOT)) {
            case "IN_PROGRESS" -> WorkflowTransition.Actor.FACILITATOR;
            case "REVIEW" -> WorkflowTransition.Actor.CONTROL_OPERATOR;
            case "PROCESS_OWNER_REVIEW" -> WorkflowTransition.Actor.PROCESS_OWNER;
            default -> null;
        };
    }

    /** The occurrence day, or the Monday after an occurrence at a weekend (the scheduler runs on working days). */
    private LocalDate firstWorkingDay(LocalDate occurrence) {
        LocalDate day = occurrence;
        while (!workingDays.isWorkingDay(day)) {
            day = day.plusDays(1);
        }
        return day;
    }

    /** Whether T1 went out for the control today: then T2 does not (decision 2). */
    private boolean initiatedOn(Control control, LocalDate today) {
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM email_outbox WHERE control_id = ? AND template_key = 'T1'"
                        + " AND created_at >= ? AND created_at < ?", Integer.class, control.getId(),
                Timestamp.valueOf(today.atStartOfDay()), Timestamp.valueOf(today.plusDays(1).atStartOfDay()));
        return count != null && count > 0;
    }

    private synchronized void warn(LocalDate today, String message) {
        if (!today.equals(warnedOn)) {
            warned.clear();
            warnedOn = today;
        }
        if (warned.add(message)) {
            log.warn(message);
        }
    }
}
