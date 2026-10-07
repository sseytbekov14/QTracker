package com.kpmg.qtracker.service;

import com.kpmg.qtracker.enums.ControlFrequency;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReminderDaysTest {

    private static final int[] MONTHLY = {3, 6};
    private static final int[] QUARTERLY = {5, 12};
    private static final int[] ANNUAL = {5, 25};

    private final WorkingDaysService workingDays = new WorkingDaysService();

    @Test
    void monthly_bothRemindersBeforeTheFourteenDayDeadline_unchanged() {
        LocalDate operationDate = LocalDate.of(2026, 10, 7);   // Wednesday, deadline 21.10

        assertThat(ReminderDays.dates(workingDays, operationDate, operationDate.plusDays(14), MONTHLY))
                .containsExactly(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 15));
    }

    @Test
    void day12AndDay25_afterTheDeadline_goOutOnTheDeadlineDay() {
        LocalDate operationDate = LocalDate.of(2026, 10, 7);
        LocalDate deadline = LocalDate.of(2026, 10, 21);

        assertThat(ReminderDays.dates(workingDays, operationDate, deadline, QUARTERLY))
                .containsExactly(LocalDate.of(2026, 10, 14), deadline);
        assertThat(ReminderDays.dates(workingDays, operationDate, deadline, ANNUAL))
                .containsExactly(LocalDate.of(2026, 10, 14), deadline);
    }

    @Test
    void deadlineOnAWeekend_theLastReminderOnTheFridayBefore() {
        LocalDate operationDate = LocalDate.of(2026, 10, 10);  // Saturday, deadline Saturday 24.10

        assertThat(ReminderDays.dates(workingDays, operationDate, operationDate.plusDays(14), QUARTERLY))
                .containsExactly(LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 23));
    }

    @Test
    void anOlderShorterDeadline_day6MovesToTheDeadline() {
        // Monthly under the earlier rule: deadline + 7 days; DAY 6 (Monday 19.10) came after it
        LocalDate operationDate = LocalDate.of(2026, 10, 9);   // Friday

        assertThat(ReminderDays.dates(workingDays, operationDate, LocalDate.of(2026, 10, 16), MONTHLY))
                .containsExactly(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 10, 16));
    }

    @Test
    void twoRemindersOnOneDay_onlyTheFirst() {
        LocalDate operationDate = LocalDate.of(2026, 10, 7);

        assertThat(ReminderDays.dates(workingDays, operationDate, LocalDate.of(2026, 10, 12), QUARTERLY))
                .containsExactly(LocalDate.of(2026, 10, 12), null);
    }

    @Test
    void deadlineOnOrBeforeTheOperationDate_noReminders() {
        LocalDate operationDate = LocalDate.of(2026, 10, 7);

        assertThat(ReminderDays.dates(workingDays, operationDate, operationDate, QUARTERLY)).containsOnlyNulls();
        assertThat(ReminderDays.dates(workingDays, operationDate, operationDate.minusDays(3), QUARTERLY))
                .containsOnlyNulls();
    }

    @Test
    void withoutADeadline_theWorkingDaysAsBefore() {
        LocalDate operationDate = LocalDate.of(2026, 10, 7);

        assertThat(ReminderDays.dates(workingDays, operationDate, null, ANNUAL))
                .containsExactly(LocalDate.of(2026, 10, 14), LocalDate.of(2026, 11, 11));
        assertThat(ReminderDays.dates(workingDays, null, LocalDate.of(2026, 10, 21), ANNUAL)).containsOnlyNulls();
    }

    /** Every operation day of three years, every frequency's reminders, with the 14-day deadline. */
    @Test
    void everyReminder_isAfterTheOperationDate_onOrBeforeTheDeadline_onAWorkingDay() {
        ControlScheduleCalculator calculator = new ControlScheduleCalculator();
        for (LocalDate operationDate = LocalDate.of(2026, 1, 1);
             operationDate.isBefore(LocalDate.of(2029, 1, 1));
             operationDate = operationDate.plusDays(1)) {
            for (int[] offsets : List.of(MONTHLY, QUARTERLY, ANNUAL)) {
                LocalDate deadline = calculator.calculateDeadline(ControlFrequency.QUARTERLY, operationDate);
                LocalDate[] dates = ReminderDays.dates(workingDays, operationDate, deadline, offsets);

                assertThat(dates[0]).as("first reminder %s", operationDate).isNotNull();
                assertThat(dates[1]).as("second reminder %s", operationDate).isNotNull().isAfter(dates[0]);
                for (LocalDate date : dates) {
                    assertThat(date).isAfter(operationDate).isBeforeOrEqualTo(deadline);
                    assertThat(workingDays.isWorkingDay(date)).isTrue();
                }
            }
        }
    }

    @Test
    void isDue_onlyOnTheReminderDay() {
        LocalDate operationDate = LocalDate.of(2026, 10, 7);
        LocalDate deadline = LocalDate.of(2026, 10, 21);

        assertThat(ReminderDays.isDue(workingDays, deadline, operationDate, deadline, 12, QUARTERLY)).isTrue();
        assertThat(ReminderDays.isDue(workingDays, LocalDate.of(2026, 10, 23), operationDate, deadline, 12, QUARTERLY))
                .isFalse();
        assertThat(ReminderDays.isDue(workingDays, LocalDate.of(2026, 10, 14), operationDate, deadline, 5, QUARTERLY))
                .isTrue();
        assertThatThrownBy(() -> ReminderDays.isDue(workingDays, deadline, operationDate, deadline, 7, QUARTERLY))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
