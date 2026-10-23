package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.DashboardChartDataDTO;
import com.kpmg.qtracker.repository.ControlRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The organisation's frequency chart counts every control's frequency, read as one column. */
@ExtendWith(MockitoExtension.class)
class DashboardServiceFrequencyTest {

    @Mock
    private ControlRepository controlRepository;

    @Mock
    private IControlService controlService;

    @Mock
    private ControlAssignmentService controlAssignmentService;

    @Test
    void everyFrequency_isCounted_noneAsUnspecified_anOtherOneUnderItsName() {
        when(controlRepository.findAllControlFrequencies()).thenReturn(Arrays.asList(
                "Monthly", "monthly ", "Quarterly", "Ad-hoc", "Semi Annual", null, "", "Weekly", "Annual", "Recurring"));

        DashboardChartDataDTO chart = new DashboardService(controlRepository, controlService, controlAssignmentService)
                .getFrequencyBreakdown();

        assertThat(chart.getLabels())
                .containsExactly("Monthly", "Quarterly", "Recurring", "Annual", "Semi Annual", "Ad-Hoc", "Unspecified", "Weekly");
        assertThat(chart.getValues()).containsExactly(2L, 1L, 1L, 1L, 1L, 1L, 2L, 1L);
        verify(controlRepository, never()).findAll();
        verify(controlRepository, never()).findAllById(any());
    }
}
