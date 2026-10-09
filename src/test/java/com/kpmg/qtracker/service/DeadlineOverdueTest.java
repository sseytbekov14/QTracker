package com.kpmg.qtracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.dto.DashboardDeadlineCountdownItemDTO;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DeadlineOverdueTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 1);

    @Test
    void deadlineToday_isNotOverdue() {
        assertThat(DeadlineOverdue.isOverdue(TODAY, TODAY)).isFalse();
        assertThat(DeadlineOverdue.daysOverdue(TODAY, TODAY)).isZero();
    }

    @Test
    void deadlineYesterday_isOverdueByOneDay() {
        LocalDate yesterday = TODAY.minusDays(1);

        assertThat(DeadlineOverdue.isOverdue(yesterday, TODAY)).isTrue();
        assertThat(DeadlineOverdue.daysOverdue(yesterday, TODAY)).isEqualTo(1L);
    }

    @Test
    void daysOverdue_countsCalendarDaysAcrossMonths() {
        assertThat(DeadlineOverdue.daysOverdue(LocalDate.of(2026, 9, 21), TODAY)).isEqualTo(10L);
    }

    @Test
    void futureOrMissingDeadline_isNotOverdue() {
        assertThat(DeadlineOverdue.isOverdue(TODAY.plusDays(1), TODAY)).isFalse();
        assertThat(DeadlineOverdue.daysOverdue(TODAY.plusDays(1), TODAY)).isZero();
        assertThat(DeadlineOverdue.isOverdue(null, TODAY)).isFalse();
        assertThat(DeadlineOverdue.daysOverdue(null, TODAY)).isZero();
    }

    @Test
    void today_isTheAlmatyDay_notTheUtcDay() {
        // 19:30 UTC on Oct 1 is already 00:30 on Oct 2 in Almaty (UTC+5)
        LocalDate today = DeadlineOverdue.today(Instant.parse("2026-10-01T19:30:00Z"));

        assertThat(today).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(DeadlineOverdue.daysOverdue(TODAY, today)).isEqualTo(1L);
    }

    @Test
    void lastSecondOfTheAlmatyDay_deadlineTodayIsStillNotOverdue() {
        // 18:59:59 UTC is 23:59:59 in Almaty
        LocalDate today = DeadlineOverdue.today(Instant.parse("2026-10-01T18:59:59Z"));

        assertThat(today).isEqualTo(TODAY);
        assertThat(DeadlineOverdue.isOverdue(TODAY, today)).isFalse();
    }

    @Test
    void earlyMorningInAlmaty_isAlreadyTheNextDayWhileUtcIsStillYesterday() {
        // 20:00 UTC on Sep 30 is 01:00 on Oct 1 in Almaty: a Sep 30 deadline is overdue
        LocalDate today = DeadlineOverdue.today(Instant.parse("2026-09-30T20:00:00Z"));

        assertThat(today).isEqualTo(TODAY);
        assertThat(DeadlineOverdue.isOverdue(LocalDate.of(2026, 9, 30), today)).isTrue();
    }

    @Test
    void countdownItem_serializesDeadlineAsEndOfAlmatyDayWithOffset() throws Exception {
        DashboardDeadlineCountdownItemDTO item = new DashboardDeadlineCountdownItemDTO(
                1L, "HR-CTRL-1", "Name", DeadlineOverdue.endOfDay(TODAY.minusDays(3)),
                "IN_PROGRESS", "/view-control/1", true, 3L);

        JsonNode json = new ObjectMapper().findAndRegisterModules().valueToTree(item);

        assertThat(json.get("deadline").asText()).isEqualTo("2026-09-28T23:59:00+05:00");
        assertThat(json.get("overdue").asBoolean()).isTrue();
        assertThat(json.get("daysOverdue").asLong()).isEqualTo(3L);
    }

    // ===== The control rule: status + deadline =====

    @Test
    void openControlPastItsDeadline_isOverdue_draftsIncluded() {
        LocalDate yesterday = TODAY.minusDays(1);

        for (String status : List.of("IN_PROGRESS", "REVIEW", "SOQM_HEAD_REVIEW", "PROCESS_OWNER_REVIEW", "DRAFT")) {
            assertThat(DeadlineOverdue.isOverdue(status, yesterday, TODAY)).as(status).isTrue();
            assertThat(DeadlineOverdue.daysOverdue(status, yesterday, TODAY)).as(status).isEqualTo(1L);
        }
        // A missing status is a draft
        assertThat(DeadlineOverdue.isOverdue(null, yesterday, TODAY)).isTrue();
        assertThat(DeadlineOverdue.isOverdue(" ", yesterday, TODAY)).isTrue();
    }

    @Test
    void completedControl_isNeverOverdue_whateverItsDeadline() {
        assertThat(DeadlineOverdue.isCompleted("COMPLETED")).isTrue();
        assertThat(DeadlineOverdue.isCompleted(" completed ")).isTrue();
        assertThat(DeadlineOverdue.isCompleted("PROCESS_OWNER_REVIEW")).isFalse();
        assertThat(DeadlineOverdue.isCompleted(null)).isFalse();

        assertThat(DeadlineOverdue.isOverdue("COMPLETED", TODAY.minusDays(30), TODAY)).isFalse();
        assertThat(DeadlineOverdue.daysOverdue("COMPLETED", TODAY.minusDays(30), TODAY)).isZero();
    }

    @Test
    void controlDueToday_orWithoutDeadline_isNotOverdue() {
        assertThat(DeadlineOverdue.isOverdue("IN_PROGRESS", TODAY, TODAY)).isFalse();
        assertThat(DeadlineOverdue.daysOverdue("IN_PROGRESS", TODAY, TODAY)).isZero();
        assertThat(DeadlineOverdue.isOverdue("IN_PROGRESS", null, TODAY)).isFalse();
    }

    @Test
    void closedLate_onlyForCompletedControlsFinishedAfterTheDeadlineDay() {
        LocalDate deadline = TODAY.minusDays(5);

        assertThat(DeadlineOverdue.isClosedLate("COMPLETED", deadline, deadline.plusDays(1))).isTrue();
        assertThat(DeadlineOverdue.isClosedLate("COMPLETED", deadline, deadline)).isFalse();
        assertThat(DeadlineOverdue.isClosedLate("COMPLETED", deadline, deadline.minusDays(1))).isFalse();
        assertThat(DeadlineOverdue.isClosedLate("COMPLETED", deadline, null)).isFalse();
        assertThat(DeadlineOverdue.isClosedLate("COMPLETED", null, TODAY)).isFalse();
        // Still open: overdue, not closed late
        assertThat(DeadlineOverdue.isClosedLate("REVIEW", deadline, TODAY)).isFalse();
    }

    @Test
    void dueSoon_isOpenAndDueFromTodayToTheLastDayOfTheWindow() {
        assertThat(DeadlineOverdue.isDueSoon("REVIEW", TODAY, TODAY, 3)).isTrue();
        assertThat(DeadlineOverdue.isDueSoon("REVIEW", TODAY.plusDays(3), TODAY, 3)).isTrue();
        assertThat(DeadlineOverdue.isDueSoon("DRAFT", TODAY.plusDays(1), TODAY, 3)).isTrue();
        assertThat(DeadlineOverdue.isDueSoon("REVIEW", TODAY.plusDays(4), TODAY, 3)).isFalse();
        assertThat(DeadlineOverdue.isDueSoon("REVIEW", TODAY.minusDays(1), TODAY, 3)).isFalse();
        assertThat(DeadlineOverdue.isDueSoon("COMPLETED", TODAY.plusDays(1), TODAY, 3)).isFalse();
        assertThat(DeadlineOverdue.isDueSoon("REVIEW", null, TODAY, 3)).isFalse();
    }

    @Test
    void deadlineOf_prefersTheOperationDeadline() {
        LocalDate operation = TODAY.plusDays(2);
        LocalDate stored = TODAY.plusDays(9);

        assertThat(DeadlineOverdue.deadlineOf(operation, stored)).isEqualTo(operation);
        assertThat(DeadlineOverdue.deadlineOf(null, stored)).isEqualTo(stored);
        assertThat(DeadlineOverdue.deadlineOf(null, null)).isNull();
    }

    @Test
    void count_putsEveryControlInExactlyOneOfCompletedOverdueActive() {
        record Row(String status, LocalDate deadline) {
        }
        List<Row> rows = Arrays.asList(
                new Row("COMPLETED", TODAY.minusDays(10)),   // closed late: completed, not overdue
                new Row("COMPLETED", null),
                new Row("REVIEW", TODAY.minusDays(1)),      // overdue
                new Row("DRAFT", TODAY.minusDays(3)),       // overdue draft
                new Row("IN_PROGRESS", TODAY),              // due today: active
                new Row(null, null),                        // draft without deadline: active
                null);

        DeadlineOverdue.Counts counts = DeadlineOverdue.count(rows, Row::status, Row::deadline, TODAY);

        assertThat(counts).isEqualTo(new DeadlineOverdue.Counts(6, 2, 2, 2));
        assertThat(counts.active() + counts.completed() + counts.overdue()).isEqualTo(counts.total());
    }
}
