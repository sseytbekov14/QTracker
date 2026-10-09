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
    void deadlineRule_isFourteenCalendarDays() {
        assertThat(ControlScheduleCalculator.DEADLINE.days()).isEqualTo(14);
        assertThat(ControlScheduleCalculator.DEADLINE.count()).isEqualTo(ControlScheduleCalculator.DayCount.CALENDAR);
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
        LocalDate operationDate = LocalDate.of(2026, 1, 31);

        assertThat(calculator.calculateDeadline(frequency, operationDate)).isEqualTo(expectedDeadline);
        assertThat(calculator.calculateNextDate(frequency, operationDate)).isEqualTo(expectedNextDate);
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> monthEndCases() {
        return Stream.of(
                arguments(ControlFrequency.MONTHLY, LocalDate.of(2026, 2, 14), LocalDate.of(2026, 2, 28)),
                arguments(ControlFrequency.QUARTERLY, LocalDate.of(2026, 2, 14), LocalDate.of(2026, 4, 30)),
                arguments(ControlFrequency.RECURRING, LocalDate.of(2026, 2, 14), LocalDate.of(2026, 4, 30)),
                arguments(ControlFrequency.AD_HOC, LocalDate.of(2026, 2, 14), null),
                arguments(ControlFrequency.SEMI_ANNUAL, LocalDate.of(2026, 2, 14), LocalDate.of(2026, 7, 31)),
                arguments(ControlFrequency.ANNUAL, LocalDate.of(2026, 2, 14), LocalDate.of(2027, 1, 31))
        );
    }

    /** 14 calendar days for every frequency, across month and year ends, in leap and common years. */
    @ParameterizedTest
    @MethodSource("deadlineDates")
    void deadline_isTheOperationDatePlusFourteenDays_forEveryFrequency(LocalDate operationDate, LocalDate expected) {
        for (ControlFrequency frequency : ControlFrequency.values()) {
            assertThat(calculator.calculateDeadline(frequency, operationDate))
                    .as("%s %s", frequency, operationDate)
                    .isEqualTo(expected);
        }
    }

    static Stream<org.junit.jupiter.params.provider.Arguments> deadlineDates() {
        return Stream.of(
                arguments(LocalDate.of(2026, 1, 31), LocalDate.of(2026, 2, 14)),
                arguments(LocalDate.of(2026, 2, 15), LocalDate.of(2026, 3, 1)),    // common February: 28 days
                arguments(LocalDate.of(2028, 2, 15), LocalDate.of(2028, 2, 29)),   // leap February
                arguments(LocalDate.of(2028, 2, 16), LocalDate.of(2028, 3, 1)),
                arguments(LocalDate.of(2028, 2, 29), LocalDate.of(2028, 3, 14)),   // leap day itself
                arguments(LocalDate.of(2026, 4, 30), LocalDate.of(2026, 5, 14)),
                arguments(LocalDate.of(2026, 12, 18), LocalDate.of(2027, 1, 1)),   // into the next year
                arguments(LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 14)),
                arguments(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 24))  // a Saturday stays a Saturday
        );
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
