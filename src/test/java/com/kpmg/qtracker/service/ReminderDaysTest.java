package com.kpmg.qtracker.service;

import com.kpmg.qtracker.enums.ControlFrequency;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ReminderDaysTest {

    private static final int[] MONTHLY = {3, 6};
    private static final int[] QUARTERLY = {5, 12};
    private static final int[] ANNUAL = {5, 25};

    private final WorkingDaysService workingDays = new WorkingDaysService();

    @Test
    void monthly_bothRemindersBeforeTheTenWorkingDayDeadline_unchanged() {
        LocalDate operationDate = LocalDate.of(2026, 10, 7);   // Wednesday, deadline 21.10

        assertThat(ReminderDays.dates(workingDays, operationDate, LocalDate.of(2026, 10, 21), MONTHLY))
                .containsExactly(LocalDate.of(2026, 10, 12), LocalDate.of(2026, 10, 15));
    }

    @Test
    void operationDateOnASaturday_remindersCountFromMonday_secondOneOnTheFridayDeadline() {
        LocalDate operationDate = LocalDate.of(2026, 10, 17);  // Saturday, deadline Friday 30.10
        LocalDate deadline = new ControlScheduleCalculator().calculateDeadline(ControlFrequency.QUARTERLY, operationDate);

        assertThat(deadline).isEqualTo(LocalDate.of(2026, 10, 30));
        assertThat(ReminderDays.dates(workingDays, operationDate, deadline, MONTHLY))
                .containsExactly(LocalDate.of(2026, 10, 21), LocalDate.of(2026, 10, 26));
        assertThat(ReminderDays.dates(workingDays, operationDate, deadline, QUARTERLY))
                .containsExactly(LocalDate.of(2026, 10, 23), deadline);
        assertThat(ReminderDays.dates(workingDays, operationDate, deadline, ANNUAL))
                .containsExactly(LocalDate.of(2026, 10, 23), deadline);
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
        // A deadline stored under the earlier rule of 14 calendar days: Saturday 10.10 -> Saturday 24.10
        LocalDate operationDate = LocalDate.of(2026, 10, 10);

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

    /**
     * Every operation day of three years, every frequency with its own reminders (ReminderNotificationService and
     * the per-frequency services use the same offsets), with the deadline of 10 working days: both reminders go
     * out, on working days, after the operation date and on or before the deadline. DAY 3 / DAY 5 / DAY 6 keep
     * their own day; DAY 12 and DAY 25 go out on the deadline day.
     */
    @Test
    void everyReminder_isAfterTheOperationDate_onOrBeforeTheDeadline_onAWorkingDay() {
        ControlScheduleCalculator calculator = new ControlScheduleCalculator();
        Map<ControlFrequency, int[]> offsetsOf = Map.of(
                ControlFrequency.MONTHLY, MONTHLY,
                ControlFrequency.QUARTERLY, QUARTERLY,
                ControlFrequency.RECURRING, QUARTERLY,
                ControlFrequency.AD_HOC, QUARTERLY,
                ControlFrequency.SEMI_ANNUAL, ANNUAL,
                ControlFrequency.ANNUAL, ANNUAL);
        for (LocalDate operationDate = LocalDate.of(2026, 1, 1);
             operationDate.isBefore(LocalDate.of(2029, 1, 1));
             operationDate = operationDate.plusDays(1)) {
            for (Map.Entry<ControlFrequency, int[]> frequency : offsetsOf.entrySet()) {
                int[] offsets = frequency.getValue();
                LocalDate deadline = calculator.calculateDeadline(frequency.getKey(), operationDate);
                LocalDate[] dates = ReminderDays.dates(workingDays, operationDate, deadline, offsets);
                String as = frequency.getKey() + " " + operationDate;

                assertThat(dates[0]).as("first reminder %s", as).isNotNull();
                assertThat(dates[1]).as("second reminder %s", as).isNotNull().isAfter(dates[0]);
                for (LocalDate date : dates) {
                    assertThat(date).as(as).isAfter(operationDate).isBeforeOrEqualTo(deadline);
                    assertThat(workingDays.isWorkingDay(date)).as(as).isTrue();
                }
                assertThat(dates[0]).as(as).isEqualTo(workingDays.addWorkingDays(operationDate, offsets[0]))
                        .isBefore(deadline);
                assertThat(dates[1]).as(as).isEqualTo(offsets[1] < 10
                        ? workingDays.addWorkingDays(operationDate, offsets[1])
                        : deadline);
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
