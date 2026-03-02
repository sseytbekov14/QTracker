package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.dto.DashboardChartDataDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.WorkflowHistoryRepository;
import com.kpmg.qtracker.repository.WorkflowStepRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceMyScopeTest {

    @Mock
    private ControlRepository controlRepository;

    @Mock
    private IControlService controlService;

    @Mock
    private ControlAssignmentService controlAssignmentService;

    @Mock
    private WorkflowStepRepository workflowStepRepository;

    @Mock
    private WorkflowHistoryRepository workflowHistoryRepository;

    private DashboardService dashboardService;

    @BeforeEach
    void setUp() {
        dashboardService = new DashboardService(
                controlRepository,
                controlService,
                controlAssignmentService,
                workflowStepRepository,
                workflowHistoryRepository
        );
    }

    @Test
    void myFrequencyBreakdown_countsOnlyControlsVisibleToCurrentUser() {
        User currentUser = buildUser(7L, "reviewer@kpmg.kz", "FACILITATOR");

        Control monthlyVisible = buildControl(100L, "Monthly", "HR");
        Control quarterlyNotVisible = buildControl(101L, "Quarterly", "GOV");
        Control annualVisibleById = buildControl(102L, "Annual", "A&C");
        Control draftVisible = buildControl(103L, "Recurring", "GOV");
        draftVisible.setPerformanceStatus("DRAFT");

        when(controlService.findVisibleControlsForUser("reviewer@kpmg.kz", "FACILITATOR"))
                .thenReturn(List.of(monthlyVisible, quarterlyNotVisible, annualVisibleById, draftVisible));
        when(controlAssignmentService.getAssignmentByControlId(100L))
                .thenReturn(assignment(List.of("reviewer@kpmg.kz"), List.of(), List.of(), List.of()));
        when(controlAssignmentService.getAssignmentByControlId(101L))
                .thenReturn(assignment(List.of("other@kpmg.kz"), List.of(), List.of(), List.of()));
        when(controlAssignmentService.getAssignmentByControlId(102L))
                .thenReturn(assignment(List.of(), List.of(), List.of("7"), List.of()));
        when(controlAssignmentService.getAssignmentByControlId(103L))
                .thenReturn(assignment(List.of("reviewer@kpmg.kz"), List.of(), List.of(), List.of()));

        DashboardChartDataDTO result = dashboardService.getMyFrequencyBreakdown(currentUser);

        assertThat(result.getLabels()).containsExactly("Monthly", "Annual");
        assertThat(result.getValues()).containsExactly(1L, 1L);
        assertThat(result.getValues().stream().mapToLong(Long::longValue).sum()).isEqualTo(2L);
    }

    @Test
    void myComponentBreakdown_countsOnlyControlsVisibleToCurrentUser() {
        User currentUser = buildUser(9L, "operator@kpmg.kz", "CONTROL_OPERATOR");

        Control hrVisible = buildControl(200L, "Monthly", "HR");
        Control acVisibleShared = buildControl(201L, "Quarterly", "A&C");
        Control govNotVisible = buildControl(202L, "Annual", "GOV");
        Control draftVisible = buildControl(203L, "Recurring", "RAP");
        draftVisible.setPerformanceStatus("DRAFT");

        when(controlService.findVisibleControlsForUser("operator@kpmg.kz", "CONTROL_OPERATOR"))
                .thenReturn(List.of(hrVisible, acVisibleShared, govNotVisible, draftVisible));
        when(controlAssignmentService.getAssignmentByControlId(200L))
                .thenReturn(assignment(List.of(), List.of("operator@kpmg.kz"), List.of(), List.of()));
        when(controlAssignmentService.getAssignmentByControlId(201L))
                .thenReturn(assignment(List.of(), List.of(), List.of(), List.of("operator@kpmg.kz")));
        when(controlAssignmentService.getAssignmentByControlId(202L))
                .thenReturn(assignment(List.of(), List.of(), List.of("other@kpmg.kz"), List.of()));
        when(controlAssignmentService.getAssignmentByControlId(203L))
                .thenReturn(assignment(List.of("operator@kpmg.kz"), List.of(), List.of(), List.of()));

        DashboardChartDataDTO result = dashboardService.getMyComponentBreakdown(currentUser);

        assertThat(result.getLabels()).containsExactly("HR", "A&C");
        assertThat(result.getValues()).containsExactly(1L, 1L);
        assertThat(result.getValues().stream().mapToLong(Long::longValue).sum()).isEqualTo(2L);
    }

    private User buildUser(Long id, String email, String role) {
        User user = new User();
        user.setId(id);
        user.setMail(email);
        user.setRole(role);
        return user;
    }

    private Control buildControl(Long id, String frequency, String component) {
        Control control = new Control();
        control.setId(id);
        control.setControlFrequency(frequency);
        control.setComponent(component);
        control.setPerformanceStatus("IN_PROGRESS");
        return control;
    }

    private ControlAssignmentDTO assignment(List<String> facilitators,
                                            List<String> controlOperators,
                                            List<String> processOwners,
                                            List<String> sharedWith) {
        ControlAssignmentDTO dto = new ControlAssignmentDTO();
        dto.setFacilitator(facilitators);
        dto.setControlOperator(controlOperators);
        dto.setProcessOwner(processOwners);
        dto.setControlSharedWith(sharedWith);
        return dto;
    }
}
