package com.kpmg.qtracker.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Collection;
import java.util.function.Function;

/**
 * Overdue rules for day-based deadlines. A deadline is a calendar day in Almaty:
 * the control is due until the end of that day and becomes overdue the next day.
 *
 * <p>This is the one place that decides whether a control is overdue. The dashboard tiles,
 * the deadlines block and calendar, the Overdue filter on Controls, Action Centre, the control
 * pages and Needs attention all call it; none of them repeat the rule.
 */
public final class DeadlineOverdue {
    private static final ZoneId ZONE = ZoneId.of("Asia/Almaty");

    private DeadlineOverdue() {
    }

    // The business day in Almaty, not the server/DB timezone (UTC in Docker)
    public static LocalDate today(Instant now) {
        return LocalDate.ofInstant(now, ZONE);
    }

    public static boolean isOverdue(LocalDate deadline, LocalDate today) {
        return deadline != null && today != null && deadline.isBefore(today);
    }

    // 0 when the deadline is today or later
    public static long daysOverdue(LocalDate deadline, LocalDate today) {
        return isOverdue(deadline, today) ? ChronoUnit.DAYS.between(deadline, today) : 0L;
    }

    // Last minute of the deadline day in Almaty, so browsers in any timezone count down to the same moment
    public static OffsetDateTime endOfDay(LocalDate deadline) {
        return deadline == null ? null : deadline.atTime(23, 59).atZone(ZONE).toOffsetDateTime();
    }

    /** Completed by the control's current status only; workflow history is not consulted. */
    public static boolean isCompleted(String performanceStatus) {
        return performanceStatus != null && "COMPLETED".equalsIgnoreCase(performanceStatus.trim());
    }

    /** Not completed (drafts included) and past the deadline day. */
    public static boolean isOverdue(String performanceStatus, LocalDate deadline, LocalDate today) {
        return !isCompleted(performanceStatus) && isOverdue(deadline, today);
    }

    // 0 unless the control is overdue
    public static long daysOverdue(String performanceStatus, LocalDate deadline, LocalDate today) {
        return isOverdue(performanceStatus, deadline, today) ? daysOverdue(deadline, today) : 0L;
    }

    /** Not completed and due between today and {@code days} days ahead. */
    public static boolean isDueSoon(String performanceStatus, LocalDate deadline, LocalDate today, int days) {
        return !isCompleted(performanceStatus)
                && deadline != null
                && today != null
                && !deadline.isBefore(today)
                && !deadline.isAfter(today.plusDays(Math.max(days, 0)));
    }

    /** Completed on a day after the deadline: labelled "Closed late", never counted as overdue. */
    public static boolean isClosedLate(String performanceStatus, LocalDate deadline, LocalDate completedOn) {
        return isCompleted(performanceStatus) && deadline != null && completedOn != null && completedOn.isAfter(deadline);
    }

    /** The control's deadline: the operation deadline from its assignment, else the deadline stored on the control. */
    public static LocalDate deadlineOf(LocalDate operationDeadline, LocalDate controlDeadline) {
        return operationDeadline != null ? operationDeadline : controlDeadline;
    }

    /** Each control counts once: completed, overdue or active. */
    public record Counts(long total, long active, long completed, long overdue) {
    }

    public static <T> Counts count(Collection<T> controls,
                                   Function<T, String> status,
                                   Function<T, LocalDate> deadline,
                                   LocalDate today) {
        long total = 0;
        long completed = 0;
        long overdue = 0;
        if (controls != null) {
            for (T control : controls) {
                if (control == null) {
                    continue;
                }
                total++;
                String controlStatus = status.apply(control);
                if (isCompleted(controlStatus)) {
                    completed++;
                } else if (isOverdue(controlStatus, deadline.apply(control), today)) {
                    overdue++;
                }
            }
        }
        return new Counts(total, total - completed - overdue, completed, overdue);
    }
}
