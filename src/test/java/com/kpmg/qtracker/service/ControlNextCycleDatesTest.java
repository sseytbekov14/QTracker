package com.kpmg.qtracker.service;

import com.kpmg.qtracker.enums.ControlFrequency;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;

/** The dates of a cycle made with Create Control: from the source's next date, by the rules of every control. */
class ControlNextCycleDatesTest {

    private final ControlScheduleCalculator calculator = new ControlScheduleCalculator();

    @Test
    void monthly_operationDateIsTheSourcesNextDate() {
        ControlNextCycle.Dates dates = ControlNextCycle.dates(calculator, ControlFrequency.MONTHLY, LocalDate.of(2026, 11, 10));

        assertThat(dates.operationDate()).isEqualTo(LocalDate.of(2026, 11, 10));
        // Tuesday + 10 working days
        assertThat(dates.deadline()).isEqualTo(LocalDate.of(2026, 11, 24));
        assertThat(dates.nextOperationDate()).isEqualTo(LocalDate.of(2026, 12, 10));
        assertThat(dates.soqmYear()).isEqualTo("1 OCT 2026 - 30 SEP 2027");
    }

    @Test
    void quarterly_nextIsThreeCalendarMonths() {
        ControlNextCycle.Dates dates = ControlNextCycle.dates(calculator, ControlFrequency.QUARTERLY, LocalDate.of(2027, 1, 4));

        assertThat(dates.deadline()).isEqualTo(LocalDate.of(2027, 1, 18));
        assertThat(dates.nextOperationDate()).isEqualTo(LocalDate.of(2027, 4, 4));
        assertThat(dates.soqmYear()).isEqualTo("1 OCT 2026 - 30 SEP 2027");
    }

    @Test
    void deadlineAndNext_areTheCalculatorsOwn() {
        for (ControlFrequency frequency : new ControlFrequency[]{ControlFrequency.MONTHLY, ControlFrequency.QUARTERLY}) {
            for (LocalDate day = LocalDate.of(2026, 9, 1); day.isBefore(LocalDate.of(2028, 1, 1)); day = day.plusDays(1)) {
                ControlNextCycle.Dates dates = ControlNextCycle.dates(calculator, frequency, day);
                assertThat(dates.deadline()).isEqualTo(ControlScheduleCalculator.DEADLINE.from(day));
                assertThat(dates.nextOperationDate()).isEqualTo(calculator.calculateNextDate(frequency, day));
                assertThat(dates.soqmYear()).isEqualTo(SoqmYear.current(day));
            }
        }
    }

    @Test
    void soqmYear_followsTheNewOperationDate_acrossTheFirstOfOctober() {
        assertThat(ControlNextCycle.dates(calculator, ControlFrequency.MONTHLY, LocalDate.of(2027, 9, 30)).soqmYear())
                .isEqualTo("1 OCT 2026 - 30 SEP 2027");
        assertThat(ControlNextCycle.dates(calculator, ControlFrequency.MONTHLY, LocalDate.of(2027, 10, 1)).soqmYear())
                .isEqualTo("1 OCT 2027 - 30 SEP 2028");
    }

    @Test
    void businessPeriodDays_areOnlyRecorded_theDatesStayCalendarMonths() {
        assertThat(ControlNextCycle.BUSINESS_PERIOD_DAYS)
                .containsEntry(ControlFrequency.MONTHLY, 30)
                .containsEntry(ControlFrequency.QUARTERLY, 90)
                .hasSize(2);
        // 31 January: a calendar month later is 28 February, 30 days later would be 2 March
        assertThat(ControlNextCycle.dates(calculator, ControlFrequency.MONTHLY, LocalDate.of(2027, 1, 31)).nextOperationDate())
                .isEqualTo(LocalDate.of(2027, 2, 28));
        assertThat(ControlNextCycle.dates(calculator, ControlFrequency.QUARTERLY, LocalDate.of(2027, 1, 31)).nextOperationDate())
                .isEqualTo(LocalDate.of(2027, 4, 30));
    }

    @Test
    void noSourceNextDate_noDates() {
        assertThat(ControlNextCycle.dates(calculator, ControlFrequency.MONTHLY, null)).isNull();
    }
}
