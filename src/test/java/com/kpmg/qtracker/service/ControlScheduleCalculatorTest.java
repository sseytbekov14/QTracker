package com.kpmg.qtracker.service;

import com.kpmg.qtracker.enums.ControlFrequency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class ControlScheduleCalculatorTest {

    private final ControlScheduleCalculator calculator = new ControlScheduleCalculator();

    @Test
    void deadlineRule_isTenWorkingDays() {
        assertThat(ControlScheduleCalculator.DEADLINE.days()).isEqualTo(10);
        assertThat(ControlScheduleCalculator.DEADLINE.count()).isEqualTo(ControlScheduleCalculator.DayCount.WORKING);
        assertThat(ControlScheduleCalculator.DEADLINE.hint())
                .isEqualTo("10 working days (Monday to Friday) after the Control Operation Date");
    }

    @ParameterizedTest
    @MethodSource("standardCases")
    void calculatesDeadlineAndNextDate(ControlFrequency frequency,
                                       LocalDate expectedDeadline,
                                       LocalDate expectedNextDate) {
        LocalDate operationDate = LocalDate.of(2026, 2, 6);

        assertThat(calculator.calculateDeadline(frequency, operationDate)).isEqualTo(expectedDeadline);
        assertThat(calculator.calculateNextDate(frequency, operationDate)).isEqualTo(expectedNextDate);
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> standardCases() {
        return Stream.of(
                arguments(ControlFrequency.MONTHLY, LocalDate.of(2026, 2, 20), LocalDate.of(2026, 3, 6)),
                arguments(ControlFrequency.QUARTERLY, LocalDate.of(2026, 2, 20), LocalDate.of(2026, 5, 6)),
                arguments(ControlFrequency.RECURRING, LocalDate.of(2026, 2, 20), LocalDate.of(2026, 5, 6)),
                arguments(ControlFrequency.AD_HOC, LocalDate.of(2026, 2, 20), null),
                arguments(ControlFrequency.SEMI_ANNUAL, LocalDate.of(2026, 2, 20), LocalDate.of(2026, 8, 6)),
                arguments(ControlFrequency.ANNUAL, LocalDate.of(2026, 2, 20), LocalDate.of(2027, 2, 6))
        );
    }

    @ParameterizedTest
    @MethodSource("monthEndCases")
    void calculatesMonthEndPlusMonths(ControlFrequency frequency,
                                      LocalDate expectedDeadline,
                                      LocalDate expectedNextDate) {
        LocalDate operationDate = LocalDate.of(2026, 1, 31);   // a Saturday

        assertThat(calculator.calculateDeadline(frequency, operationDate)).isEqualTo(expectedDeadline);
        assertThat(calculator.calculateNextDate(frequency, operationDate)).isEqualTo(expectedNextDate);
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> monthEndCases() {
        return Stream.of(
                arguments(ControlFrequency.MONTHLY, LocalDate.of(2026, 2, 13), LocalDate.of(2026, 2, 28)),
                arguments(ControlFrequency.QUARTERLY, LocalDate.of(2026, 2, 13), LocalDate.of(2026, 4, 30)),
                arguments(ControlFrequency.RECURRING, LocalDate.of(2026, 2, 13), LocalDate.of(2026, 4, 30)),
                arguments(ControlFrequency.AD_HOC, LocalDate.of(2026, 2, 13), null),
                arguments(ControlFrequency.SEMI_ANNUAL, LocalDate.of(2026, 2, 13), LocalDate.of(2026, 7, 31)),
                arguments(ControlFrequency.ANNUAL, LocalDate.of(2026, 2, 13), LocalDate.of(2027, 1, 31))
        );
    }

    /**
     * 10 working days for every frequency, counted from the first working day after the operation date: the
     * examples of the business decision, month ends, 29 February, the turn of the year (holidays not counted).
     */
    @ParameterizedTest
    @MethodSource("deadlineDates")
    void deadline_isTheOperationDatePlusTenWorkingDays_forEveryFrequency(LocalDate operationDate, LocalDate expected) {
        for (ControlFrequency frequency : ControlFrequency.values()) {
            assertThat(calculator.calculateDeadline(frequency, operationDate))
                    .as("%s %s", frequency, operationDate)
                    .isEqualTo(expected);
        }
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> deadlineDates() {
        return Stream.of(
                arguments(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 26)),  // Monday -> Monday
                arguments(LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 30)),  // Friday
                arguments(LocalDate.of(2026, 10, 17), LocalDate.of(2026, 10, 30)),  // Saturday -> the same Friday
                arguments(LocalDate.of(2026, 10, 18), LocalDate.of(2026, 10, 30)),  // Sunday -> the same Friday
                arguments(LocalDate.of(2026, 1, 30), LocalDate.of(2026, 2, 13)),    // month end, Friday
                arguments(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 13)),    // month end, Saturday
                arguments(LocalDate.of(2026, 2, 28), LocalDate.of(2026, 3, 13)),    // end of a common February
                arguments(LocalDate.of(2026, 4, 30), LocalDate.of(2026, 5, 14)),
                arguments(LocalDate.of(2026, 5, 31), LocalDate.of(2026, 6, 12)),    // month end, Sunday
                arguments(LocalDate.of(2028, 2, 15), LocalDate.of(2028, 2, 29)),    // onto the leap day
                arguments(LocalDate.of(2028, 2, 26), LocalDate.of(2028, 3, 10)),    // across the leap day, Saturday
                arguments(LocalDate.of(2028, 2, 28), LocalDate.of(2028, 3, 13)),
                arguments(LocalDate.of(2028, 2, 29), LocalDate.of(2028, 3, 14)),    // the leap day itself
                arguments(LocalDate.of(2026, 12, 18), LocalDate.of(2027, 1, 1)),    // into the next year, 1 January counts
                arguments(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 14)),
                arguments(LocalDate.of(2028, 12, 30), LocalDate.of(2029, 1, 12))    // Saturday at the year end
        );
    }

    /** Every day of four years: a weekday after the operation date with exactly 10 working days up to it. */
    @Test
    void deadline_isAlwaysAWeekday_tenWorkingDaysAfterTheOperationDate() {
        WorkingDaysService workingDays = new WorkingDaysService();
        for (LocalDate day = LocalDate.of(2026, 1, 1); day.isBefore(LocalDate.of(2030, 1, 1)); day = day.plusDays(1)) {
            LocalDate deadline = calculator.calculateDeadline(ControlFrequency.MONTHLY, day);
            assertThat(workingDays.isWorkingDay(deadline)).as("%s -> %s", day, deadline).isTrue();
            assertThat(workingDays.workingDaysBetween(day, deadline)).as("%s -> %s", day, deadline).isEqualTo(10);
            assertThat(deadline).isBetween(day.plusDays(12), day.plusDays(14));
        }
    }

    @ParameterizedTest
    @EnumSource(ControlFrequency.class)
    void deadline_isDueOnItsDay_andOverdueTheDayAfter(ControlFrequency frequency) {
        LocalDate operationDate = LocalDate.of(2026, 10, 7);
        LocalDate deadline = calculator.calculateDeadline(frequency, operationDate);

        assertThat(deadline).isEqualTo(LocalDate.of(2026, 10, 21));
        assertThat(DeadlineOverdue.isOverdue("IN_PROGRESS", deadline, operationDate)).isFalse();
        assertThat(DeadlineOverdue.isOverdue("IN_PROGRESS", deadline, deadline.minusDays(1))).isFalse();
        assertThat(DeadlineOverdue.isOverdue("IN_PROGRESS", deadline, deadline)).isFalse();
        assertThat(DeadlineOverdue.daysOverdue("IN_PROGRESS", deadline, deadline)).isZero();
        assertThat(DeadlineOverdue.isOverdue("IN_PROGRESS", deadline, deadline.plusDays(1))).isTrue();
        assertThat(DeadlineOverdue.daysOverdue("IN_PROGRESS", deadline, deadline.plusDays(1))).isEqualTo(1);
        assertThat(DeadlineOverdue.isOverdue("COMPLETED", deadline, deadline.plusDays(1))).isFalse();
    }

    @Test
    void deadlineRule_inWorkingDays_skipsWeekends() {
        ControlScheduleCalculator.DeadlineRule working =
                new ControlScheduleCalculator.DeadlineRule(14, ControlScheduleCalculator.DayCount.WORKING);

        assertThat(working.from(LocalDate.of(2026, 10, 7))).isEqualTo(LocalDate.of(2026, 10, 27));
        assertThat(working.from(LocalDate.of(2026, 10, 10))).isEqualTo(LocalDate.of(2026, 10, 29));
    }

    @Test
    void throwsWhenOperationDateIsNull() {
        assertThatThrownBy(() -> calculator.calculateDeadline(ControlFrequency.MONTHLY, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("operationDate must not be null");
    }

    @Test
    void throwsWhenFrequencyIsNull() {
        assertThatThrownBy(() -> calculator.calculateDeadline(null, LocalDate.of(2026, 2, 6)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("frequency must not be null");
    }
}
