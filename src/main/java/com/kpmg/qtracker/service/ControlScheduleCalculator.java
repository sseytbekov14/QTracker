package com.kpmg.qtracker.service;

import com.kpmg.qtracker.enums.ControlFrequency;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class ControlScheduleCalculator {

    /**
     * The Control Operation Deadline of every frequency: the operation date plus 10 working days, Monday to
     * Friday, public holidays not counted (business decision 2026-10-14). Counting starts on the first working
     * day after the operation date, so the deadline is always a weekday: Monday 12.10.2026 -> Monday 26.10,
     * Friday 16.10, Saturday 17.10 and Sunday 18.10 -> Friday 30.10. The one place that sets the number of days
     * and how they are counted; view-control.js (calculateDeadline) previews the same,
     * SchedulePreviewParityTest keeps the two equal.
     */
    public static final DeadlineRule DEADLINE = new DeadlineRule(10, DayCount.WORKING);

    public enum DayCount {
        CALENDAR,
        /** Monday to Friday, as WorkingDaysService counts them (no public holidays) */
        WORKING
    }

    public record DeadlineRule(int days, DayCount count) {
        /** How View Control explains the deadline under the field, e.g. "10 working days after the ..." */
        public String hint() {
            return days + (count == DayCount.WORKING ? " working days (Monday to Friday)" : " calendar days")
                    + " after the Control Operation Date";
        }

        public LocalDate from(LocalDate operationDate) {
            return count == DayCount.WORKING
                    ? new WorkingDaysService().addWorkingDays(operationDate, days)
                    : operationDate.plusDays(days);
        }
    }

    public LocalDate calculateDeadline(ControlFrequency frequency, LocalDate operationDate) {
        validateInputs(frequency, operationDate);
        return DEADLINE.from(operationDate);
    }

    public LocalDate calculateNextDate(ControlFrequency frequency, LocalDate operationDate) {
        validateInputs(frequency, operationDate);
        return switch (frequency) {
            case MONTHLY -> plusMonths(operationDate, 1);
            case QUARTERLY -> plusMonths(operationDate, 3);
            case SEMI_ANNUAL -> plusMonths(operationDate, 6);
            case ANNUAL -> plusMonths(operationDate, 12);
            case RECURRING -> plusMonths(operationDate, 3);
            case AD_HOC -> null;
        };
    }

    /**
     * The date some months on. The last day of a month stays the last day (business decision 2026-11-26): 31.01 ->
     * 28.02 -> 31.03 -> 30.04, where plusMonths alone would keep the 28th from February on. Any other day keeps its
     * number, or the target month's last day when the month has no such day (30.11 + 3 months = 28.02).
     * view-control.js (plusMonths) previews the same, SchedulePreviewParityTest keeps the two equal.
     */
    static LocalDate plusMonths(LocalDate date, int months) {
        LocalDate next = date.plusMonths(months);
        return date.getDayOfMonth() == date.lengthOfMonth() ? next.withDayOfMonth(next.lengthOfMonth()) : next;
    }

    private void validateInputs(ControlFrequency frequency, LocalDate operationDate) {
        if (operationDate == null) {
            throw new IllegalArgumentException("operationDate must not be null");
        }
        if (frequency == null) {
            throw new IllegalArgumentException("frequency must not be null");
        }
    }
}
