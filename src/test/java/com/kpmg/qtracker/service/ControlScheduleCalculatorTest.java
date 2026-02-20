package com.kpmg.qtracker.service;

import com.kpmg.qtracker.enums.ControlFrequency;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.LocalDate;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.params.provider.Arguments.arguments;

class ControlScheduleCalculatorTest {

    private final ControlScheduleCalculator calculator = new ControlScheduleCalculator();

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
                arguments(ControlFrequency.MONTHLY, LocalDate.of(2026, 2, 13), LocalDate.of(2026, 3, 6)),
                arguments(ControlFrequency.QUARTERLY, LocalDate.of(2026, 2, 20), LocalDate.of(2026, 5, 6)),
                arguments(ControlFrequency.RECURRING, LocalDate.of(2026, 2, 20), LocalDate.of(2026, 5, 6)),
                arguments(ControlFrequency.AD_HOC, LocalDate.of(2026, 2, 20), null),
                arguments(ControlFrequency.SEMI_ANNUAL, LocalDate.of(2026, 3, 6), LocalDate.of(2026, 8, 6)),
                arguments(ControlFrequency.ANNUAL, LocalDate.of(2026, 3, 6), LocalDate.of(2027, 2, 6))
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
                arguments(ControlFrequency.MONTHLY, LocalDate.of(2026, 2, 7), LocalDate.of(2026, 2, 28)),
                arguments(ControlFrequency.QUARTERLY, LocalDate.of(2026, 2, 14), LocalDate.of(2026, 4, 30)),
                arguments(ControlFrequency.RECURRING, LocalDate.of(2026, 2, 14), LocalDate.of(2026, 4, 30)),
                arguments(ControlFrequency.AD_HOC, LocalDate.of(2026, 2, 14), null),
                arguments(ControlFrequency.SEMI_ANNUAL, LocalDate.of(2026, 2, 28), LocalDate.of(2026, 7, 31)),
                arguments(ControlFrequency.ANNUAL, LocalDate.of(2026, 2, 28), LocalDate.of(2027, 1, 31))
        );
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

