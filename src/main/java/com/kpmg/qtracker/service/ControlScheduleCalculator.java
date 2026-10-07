package com.kpmg.qtracker.service;

import com.kpmg.qtracker.enums.ControlFrequency;
import org.springframework.stereotype.Service;

import java.time.LocalDate;

@Service
public class ControlScheduleCalculator {

    /**
     * The Control Operation Deadline of every frequency: the operation date plus 14 calendar days
     * (business decision 2026-10-07). The one place that sets the number of days and how they are counted;
     * view-control.js (calculateDeadline) previews the same, SchedulePreviewParityTest keeps the two equal.
     * TODO: BUSINESS CONFIRMATION: calendar or working days, counted from the operation date or another date,
     * and whether a deadline on a weekend moves to the next working day.
     */
    public static final DeadlineRule DEADLINE = new DeadlineRule(14, DayCount.CALENDAR);

    public enum DayCount {
        CALENDAR,
        /** Monday to Friday, as WorkingDaysService counts them (no public holidays) */
        WORKING
    }

    public record DeadlineRule(int days, DayCount count) {
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
            case MONTHLY -> operationDate.plusMonths(1);
            case QUARTERLY -> operationDate.plusMonths(3);
            case SEMI_ANNUAL -> operationDate.plusMonths(6);
            case ANNUAL -> operationDate.plusMonths(12);
            case RECURRING -> operationDate.plusMonths(3);
            case AD_HOC -> null;
        };
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
