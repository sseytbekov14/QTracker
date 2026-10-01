package com.kpmg.qtracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.dto.DashboardDeadlineCountdownItemDTO;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;

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
}
