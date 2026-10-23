package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.dto.DashboardChartDataDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.support.TestUsers;
import com.kpmg.qtracker.repository.ControlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardServiceMyScopeTest {

    @Mock
    private ControlRepository controlRepository;

    @Mock
    private IControlService controlService;

    @Mock
    private ControlAssignmentService controlAssignmentService;

    private DashboardService dashboardService;

    @BeforeEach
    void setUp() {
        dashboardService = new DashboardService(
                controlRepository,
                controlService,
                controlAssignmentService
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

        when(controlService.findVisibleControlsForUser(currentUser))
                .thenReturn(List.of(monthlyVisible, quarterlyNotVisible, annualVisibleById, draftVisible));
        // All the assignments in one read, none one by one
        when(controlAssignmentService.getAssignmentsByControlIds(List.of(100L, 101L, 102L, 103L))).thenReturn(Map.of(
                100L, assignment(List.of("reviewer@kpmg.kz"), List.of(), List.of(), List.of()),
                101L, assignment(List.of("other@kpmg.kz"), List.of(), List.of(), List.of()),
                102L, assignment(List.of(), List.of(), List.of("7"), List.of()),
                103L, assignment(List.of("reviewer@kpmg.kz"), List.of(), List.of(), List.of())));

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

        when(controlService.findVisibleControlsForUser(currentUser))
                .thenReturn(List.of(hrVisible, acVisibleShared, govNotVisible, draftVisible));
        // 202 has no assignment row: not the user's, as before
        when(controlAssignmentService.getAssignmentsByControlIds(List.of(200L, 201L, 202L, 203L))).thenReturn(Map.of(
                200L, assignment(List.of(), List.of("operator@kpmg.kz"), List.of(), List.of()),
                201L, assignment(List.of(), List.of(), List.of(), List.of("operator@kpmg.kz")),
                203L, assignment(List.of("operator@kpmg.kz"), List.of(), List.of(), List.of())));

        DashboardChartDataDTO result = dashboardService.getMyComponentBreakdown(currentUser);

        assertThat(result.getLabels()).containsExactly("HR", "A&C");
        assertThat(result.getValues()).containsExactly(1L, 1L);
        assertThat(result.getValues().stream().mapToLong(Long::longValue).sum()).isEqualTo(2L);
        verify(controlAssignmentService, never()).getAssignmentByControlId(org.mockito.ArgumentMatchers.anyLong());
    }

    @Test
    void kdnCharts_coverEveryKdnControlTheySee_onItOrNot() {
        User kdn = TestUsers.user("kdn@kpmg.kz", com.kpmg.qtracker.enums.AccessLevel.READ_ONLY,
                com.kpmg.qtracker.enums.AccessScope.KDN, false);
        kdn.setId(11L);
        Control onIt = buildControl(300L, "Monthly", "HR");
        Control notOnIt = buildControl(301L, "Monthly", "GOV");
        Control draft = buildControl(302L, "Annual", "GOV");
        draft.setPerformanceStatus("DRAFT");
        when(controlService.findVisibleControlsForUser(kdn)).thenReturn(List.of(onIt, notOnIt, draft));

        DashboardChartDataDTO frequency = dashboardService.getMyFrequencyBreakdown(kdn);
        DashboardChartDataDTO component = dashboardService.getMyComponentBreakdown(kdn);

        // No assignment is looked at; drafts stay out of the charts as for everyone
        assertThat(frequency.getLabels()).containsExactly("Monthly");
        assertThat(frequency.getValues()).containsExactly(2L);
        assertThat(component.getLabels()).containsExactly("HR", "GOV");
        assertThat(component.getValues()).containsExactly(1L, 1L);
    }

    private User buildUser(Long id, String email, String role) {
        User user = new User();
        user.setId(id);
        user.setMail(email);
        TestUsers.withRole(user, role);
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
