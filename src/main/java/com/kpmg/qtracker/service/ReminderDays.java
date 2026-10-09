package com.kpmg.qtracker.service;

import java.time.LocalDate;

/**
 * The days the reminders before the deadline go out. Each reminder is a number of working days after the
 * operation date (DAY 3 and DAY 6 for Monthly, DAY 5 and DAY 12 for Quarterly, Recurring and Ad-hoc, DAY 5
 * and DAY 25 for Semi Annual and Annual), but never after the deadline: one that would come later is sent on
 * the last working day on or before the deadline. A reminder is dropped when that day is not after the
 * operation date, or not after the reminder before it, so one control never gets two on the same day.
 * With 14 days to the deadline (ControlScheduleCalculator.DEADLINE) DAY 12 and DAY 25 fell after it.
 *
 * <p>The per-frequency notification services and ReminderNotificationService both ask here.
 */
final class ReminderDays {

    private ReminderDays() {
    }

    /**
     * The day each reminder goes out, in the order of {@code workingDayOffsets} (ascending); null for a
     * reminder that is dropped. Without a deadline the reminders are not moved.
     */
    static LocalDate[] dates(WorkingDaysService workingDays, LocalDate operationDate, LocalDate deadline,
                             int... workingDayOffsets) {
        LocalDate[] dates = new LocalDate[workingDayOffsets.length];
        if (operationDate == null) {
            return dates;
        }
        LocalDate lastDay = deadline == null ? null : workingDays.addWorkingDays(deadline.plusDays(1), -1);
        LocalDate previous = operationDate;
        for (int i = 0; i < workingDayOffsets.length; i++) {
            LocalDate date = workingDays.addWorkingDays(operationDate, workingDayOffsets[i]);
            if (lastDay != null && date.isAfter(lastDay)) {
                date = lastDay;
            }
            if (date.isAfter(previous)) {
                dates[i] = date;
                previous = date;
            }
        }
        return dates;
    }

    /** Whether the reminder of {@code offset} (one of {@code workingDayOffsets}) goes out {@code today}. */
    static boolean isDue(WorkingDaysService workingDays, LocalDate today, LocalDate operationDate, LocalDate deadline,
                         int offset, int... workingDayOffsets) {
        LocalDate[] dates = dates(workingDays, operationDate, deadline, workingDayOffsets);
        for (int i = 0; i < workingDayOffsets.length; i++) {
            if (workingDayOffsets[i] == offset) {
                return today != null && today.equals(dates[i]);
            }
        }
        throw new IllegalArgumentException("Unknown reminder offset " + offset);
    }
}
