package com.kpmg.qtracker.service;

import com.kpmg.qtracker.enums.ControlFrequency;

import java.time.LocalDate;
import java.util.Map;

/**
 * The next cycle of a Monthly or Quarterly control that SoQM Admin Team creates with Create Control on the Assignment
 * tab (business decision of 2026-10-14): its dates.
 */
public final class ControlNextCycle {

    /**
     * How often the business says the cycles come: every 30 days for Monthly and every 90 for Quarterly. The dates are
     * NOT counted with these numbers: the next Control Operation Date stays one calendar month (three for Quarterly)
     * after the operation date, as ControlScheduleCalculator counts it for every control (31.01 -> 28.02, not 02.03).
     * TODO: BUSINESS CONFIRMATION: a calendar month and quarter, or exactly 30 and 90 days.
     */
    public static final Map<ControlFrequency, Integer> BUSINESS_PERIOD_DAYS =
            Map.of(ControlFrequency.MONTHLY, 30, ControlFrequency.QUARTERLY, 90);

    private ControlNextCycle() {
    }

    /**
     * The dates of the new cycle: its Control Operation Date is the source's Next Control Operation Date; the deadline
     * (10 working days), its own next date and the SoQM Year (the one the operation date falls in) are worked out
     * again by the rules every control follows.
     */
    public record Dates(LocalDate operationDate, LocalDate deadline, LocalDate nextOperationDate, String soqmYear) {
    }

    /** Null when the source has no Next Control Operation Date. */
    public static Dates dates(ControlScheduleCalculator calculator, ControlFrequency frequency, LocalDate sourceNext) {
        if (sourceNext == null || frequency == null) {
            return null;
        }
        return new Dates(sourceNext,
                calculator.calculateDeadline(frequency, sourceNext),
                calculator.calculateNextDate(frequency, sourceNext),
                SoqmYear.current(sourceNext));
    }
}
