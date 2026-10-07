package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlResponseDTO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** The "KDN" card of the Action Centre: which controls it counts and how. */
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
    void onlyControlsWhoseIdStartsWithKdn(String controlId, boolean counted) {
        List<ControlResponseDTO> visible = List.of(control(controlId, "IN_PROGRESS", TODAY), control("HR-1", "IN_PROGRESS", TODAY));

        assertThat(KdnControlsOverview.kdnControls(visible)).extracting(ControlResponseDTO::getControlId)
                .containsExactlyElementsOf(counted ? List.of(controlId) : List.of());
        assertThat(KdnControlsOverview.count(visible, TODAY).total()).isEqualTo(counted ? 1 : 0);
        // The card takes the rule from the policy, the one place it lives
        assertThat(AccessPolicy.isKdnControl(controlId)).isEqualTo(counted);
    }

    @Test
    void counts_eachControlOnce_likeTheComponentCards() {
        List<ControlResponseDTO> visible = List.of(
                control("KDN-1", null, TODAY.minusDays(3)),               // draft, overdue
                control("KDN-2", "DRAFT", null),                          // draft: active
                control("KDN-3", "IN_PROGRESS", TODAY.minusDays(1)),      // overdue
                control("KDN-4", "in_progress", TODAY),                   // due today: active
                control("KDN-5", "SOQM_HEAD_REVIEW", TODAY.minusDays(2)), // overdue
                control("KDN-6", "PROCESS_OWNER_REVIEW", null),           // active
                control("KDN-7", "COMPLETED", TODAY.minusDays(40)),       // completed late: completed, not overdue
                control("HR-8", "IN_PROGRESS", TODAY.minusDays(5)),       // not KDN: never counted
                control("X-KDN-9", "REVIEW", TODAY.minusDays(5)));

        DeadlineOverdue.Counts counts = KdnControlsOverview.count(visible, TODAY);

        assertThat(counts).isEqualTo(new DeadlineOverdue.Counts(7, 3, 1, 3));
        // The same numbers as the component cards and the Controls counters give for the same controls
        assertThat(counts).isEqualTo(DeadlineOverdue.count(visible.subList(0, 7),
                ControlResponseDTO::getPerformanceStatus, ControlResponseDTO::getDeadline, TODAY));
    }

    @Test
    void noKdnControls_zero() {
        assertThat(KdnControlsOverview.count(List.of(control("HR-1", "REVIEW", TODAY)), TODAY))
                .isEqualTo(new DeadlineOverdue.Counts(0, 0, 0, 0));
        assertThat(KdnControlsOverview.count(null, TODAY).total()).isZero();
        assertThat(KdnControlsOverview.CONTROLS_HREF).isEqualTo("/controls?kdn=1");
    }
}
