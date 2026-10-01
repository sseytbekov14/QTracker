package com.kpmg.qtracker.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;

/**
 * Overdue rules for day-based deadlines. A deadline is a calendar day in Almaty:
 * the control is due until the end of that day and becomes overdue the next day.
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
}
