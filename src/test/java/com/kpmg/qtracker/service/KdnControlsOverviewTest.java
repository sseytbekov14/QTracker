package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlResponseDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/** The "KDN controls" block of the Action Centre: which controls, in which order, and its counters. */
class KdnControlsOverviewTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 7);

    private static long seq;

    private static ControlResponseDTO control(String controlId, String status, LocalDate deadline) {
        ControlResponseDTO dto = new ControlResponseDTO();
        dto.setId(++seq);
        dto.setControlId(controlId);
        dto.setPerformanceStatus(status);
        dto.setDeadline(deadline);
        return dto;
    }

    @ParameterizedTest(name = "[{0}] -> {1}")
    @CsvSource(value = {
            "KDN-001, true",
            "KDN001, true",
            "kdn-5, true",
            "'  Kdn-7 ', true",
            "X-KDN-12, false",
            "HR-KDN-1, false",
            "'', false",
            "' ', false",
            "NULL, false"
    }, nullValues = "NULL")
    void onlyControlsWhoseIdStartsWithKdn(String controlId, boolean listed) {
        KdnControlsOverview.Overview overview = KdnControlsOverview.of(
                List.of(control(controlId, "IN_PROGRESS", TODAY), control("HR-1", "IN_PROGRESS", TODAY)), TODAY);

        assertThat(overview.total()).isEqualTo(listed ? 1 : 0);
        assertThat(overview.controls()).extracting(ControlResponseDTO::getControlId)
                .containsExactlyElementsOf(listed ? List.of(controlId) : List.of());
        // The block takes the rule from the policy, the one place it lives
        assertThat(AccessPolicy.isKdnControl(controlId)).isEqualTo(listed);
    }

    @Test
    void overdueFirst_thenOpenByTheNearestDeadline_noDeadlineAfter_completedLast() {
        List<ControlResponseDTO> visible = List.of(
                control("KDN-later", "IN_PROGRESS", TODAY.plusDays(20)),
                control("KDN-none", "REVIEW", null),
                control("KDN-done-late", "COMPLETED", TODAY.minusDays(30)),
                control("KDN-overdue-new", "REVIEW", TODAY.minusDays(1)),
                control("KDN-today", "IN_PROGRESS", TODAY),
                control("KDN-overdue-old", "DRAFT", TODAY.minusDays(9)),
                control("KDN-b-soon", "PROCESS_OWNER_REVIEW", TODAY.plusDays(2)),
                control("KDN-a-soon", "SOQM_HEAD_REVIEW", TODAY.plusDays(2)),
                control("KDN-done-soon", "COMPLETED", TODAY.plusDays(1)));

        KdnControlsOverview.Overview overview = KdnControlsOverview.of(visible, TODAY);

        assertThat(overview.controls()).extracting(ControlResponseDTO::getControlId).containsExactly(
                "KDN-overdue-old", "KDN-overdue-new",
                "KDN-today", "KDN-a-soon", "KDN-b-soon", "KDN-later", "KDN-none",
                // A completed control is never overdue and comes after the open ones, by deadline
                "KDN-done-late", "KDN-done-soon");
        assertThat(overview.controls()).filteredOn(ControlResponseDTO::isOverdue)
                .extracting(ControlResponseDTO::getControlId)
                .containsExactly("KDN-overdue-old", "KDN-overdue-new");
    }

    @Test
    void counters_eachStatusOnce_overdueByTheDeadlineRule() {
        List<ControlResponseDTO> visible = List.of(
                control("KDN-1", null, TODAY.minusDays(3)),               // draft, overdue
                control("KDN-2", "DRAFT", null),                          // draft
                control("KDN-3", "IN_PROGRESS", TODAY.minusDays(1)),      // in progress, overdue
                control("KDN-4", "in_progress", TODAY),                   // in progress, due today: not overdue
                control("KDN-5", "REVIEW", TODAY.plusDays(1)),
                control("KDN-6", "SOQM_HEAD_REVIEW", TODAY.minusDays(2)), // in review, overdue
                control("KDN-7", "PROCESS_OWNER_REVIEW", null),
                control("KDN-8", "COMPLETED", TODAY.minusDays(40)),       // completed late: not overdue
                control("KDN-9", "COMPLETED", TODAY.plusDays(4)),
                control("HR-10", "IN_PROGRESS", TODAY.minusDays(5)),      // not KDN: never counted
                control("X-KDN-11", "REVIEW", TODAY.minusDays(5)));

        KdnControlsOverview.Overview overview = KdnControlsOverview.of(visible, TODAY);

        assertThat(overview.total()).isEqualTo(9);
        assertThat(overview.drafts()).isEqualTo(2);
        assertThat(overview.inProgress()).isEqualTo(2);
        assertThat(overview.inReview()).isEqualTo(3);
        assertThat(overview.completed()).isEqualTo(2);
        assertThat(overview.overdue()).isEqualTo(3);
        assertThat(overview.drafts() + overview.inProgress() + overview.inReview() + overview.completed())
                .isEqualTo(overview.total());
        // The same count as DeadlineOverdue gives the dashboard tile and the Overdue filter of Controls
        assertThat(overview.overdue()).isEqualTo(overview.controls().stream()
                .filter(c -> DeadlineOverdue.isOverdue(c.getPerformanceStatus(), c.getDeadline(), TODAY)).count());
        assertThat(overview.controls()).noneMatch(c -> c.getControlId().startsWith("HR") || c.getControlId().startsWith("X"));
    }

    @Test
    void eightRowsShown_theRestBehindShowAll() {
        List<ControlResponseDTO> visible = new ArrayList<>();
        IntStream.rangeClosed(1, 11).forEach(i -> visible.add(control(String.format("KDN-%02d", i), "IN_PROGRESS",
                TODAY.plusDays(i))));

        KdnControlsOverview.Overview overview = KdnControlsOverview.of(visible, TODAY);

        assertThat(overview.shown()).hasSize(KdnControlsOverview.LIST_LIMIT).first()
                .extracting(ControlResponseDTO::getControlId).isEqualTo("KDN-01");
        assertThat(overview.more()).extracting(ControlResponseDTO::getControlId)
                .containsExactly("KDN-09", "KDN-10", "KDN-11");
    }

    @Test
    void noKdnControls_isEmpty() {
        KdnControlsOverview.Overview none = KdnControlsOverview.of(List.of(control("HR-1", "REVIEW", TODAY)), TODAY);
        KdnControlsOverview.Overview nothing = KdnControlsOverview.of(null, TODAY);

        for (KdnControlsOverview.Overview overview : List.of(none, nothing)) {
            assertThat(overview.empty()).isTrue();
            assertThat(overview.shown()).isEmpty();
            assertThat(overview.more()).isEmpty();
            assertThat(overview.overdue()).isZero();
        }
    }

    @Test
    void inReview_theThreeReviewStatuses() {
        assertThat(List.of("REVIEW", "soqm_head_review", " PROCESS_OWNER_REVIEW "))
                .allMatch(KdnControlsOverview::inReview);
        assertThat(java.util.Arrays.asList("DRAFT", "IN_PROGRESS", "COMPLETED", "", null))
                .noneMatch(KdnControlsOverview::inReview);
    }
}
