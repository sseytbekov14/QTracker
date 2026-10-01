package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.dto.WorkflowStepDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.WorkflowStepType;
import com.kpmg.qtracker.repository.WorkflowHistoryRepository;
import com.kpmg.qtracker.service.ControlAssignmentService;
import com.kpmg.qtracker.service.ControlPermission;
import com.kpmg.qtracker.service.ControlPermissionService;
import com.kpmg.qtracker.service.ControlService;
import com.kpmg.qtracker.service.IPerformanceService;
import com.kpmg.qtracker.service.NotificationService;
import com.kpmg.qtracker.service.WorkflowRequiredFieldService;
import com.kpmg.qtracker.service.WorkflowService;
import com.kpmg.qtracker.service.WorkflowTransitionGuard;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class WorkflowControllerTransitionGuardTest {

    private static final long CONTROL_ID = 500L;

    @Mock
    private WorkflowService workflowService;
    @Mock
    private IPerformanceService performanceService;
    @Mock
    private ControlService controlService;
    @Mock
    private ControlAssignmentService controlAssignmentService;
    @Mock
    private WorkflowHistoryRepository workflowHistoryRepository;
    @Mock
    private NotificationService notificationService;
    @Mock
    private WorkflowRequiredFieldService requiredFieldService;
    @Mock
    private ControlPermissionService controlPermissionService;

    private MockMvc mockMvc;
    private User currentUser;
    private Control control;
    private boolean expectChange;

    @BeforeEach
    void setUp() {
        WorkflowController controller = new WorkflowController(
                workflowService,
                performanceService,
                controlService,
                controlAssignmentService,
                workflowHistoryRepository,
                notificationService,
                requiredFieldService,
                controlPermissionService,
                new WorkflowTransitionGuard()
        );
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();

        currentUser = new User();
        currentUser.setMail("actor@kpmg.kz");
        currentUser.setDisplayName("Actor");

        control = new Control();
        control.setId(CONTROL_ID);
        control.setControlId("CTRL-500");
        lenient().when(controlService.getControlById(CONTROL_ID)).thenReturn(Optional.of(control));
    }

    // Every rejected transition must leave the control, its history and notifications untouched
    @AfterEach
    void nothingChanged() {
        if (!expectChange) {
            verify(controlService, never()).save(any(Control.class));
            verify(workflowHistoryRepository, never()).save(any());
            verifyNoInteractions(notificationService);
        }
    }

    // ---------- complete-control ----------

    @Test
    void completeControl_bySoqmTeam_isForbidden() throws Exception {
        givenStatus("PROCESS_OWNER_REVIEW").as(Role.SOQM_TEAM);
        call("/api/workflow/complete-control").andExpect(status().isForbidden());
    }

    @Test
    void completeControl_byProcessOwnerBeforeTheirStep_isConflict() throws Exception {
        givenStatus("SOQM_HEAD_REVIEW").as(Role.PROCESS_OWNER);
        call("/api/workflow/complete-control").andExpect(status().isConflict());
    }

    @Test
    void completeControl_alreadyCompleted_isConflict() throws Exception {
        givenStatus("COMPLETED").as(Role.PROCESS_OWNER);
        call("/api/workflow/complete-control").andExpect(status().isConflict());
    }

    // ---------- submit-to-process-owner ----------

    @Test
    void submitToProcessOwner_byFacilitator_isForbidden() throws Exception {
        givenStatus("SOQM_HEAD_REVIEW").as(Role.FACILITATOR);
        call("/api/workflow/submit-to-process-owner").andExpect(status().isForbidden());
    }

    @Test
    void submitToProcessOwner_bySoqmTeamInReview_isConflict() throws Exception {
        givenStatus("REVIEW").as(Role.SOQM_TEAM);
        call("/api/workflow/submit-to-process-owner").andExpect(status().isConflict());
    }

    // ---------- return-to-operator ----------

    @Test
    void returnToOperator_byProcessOwner_isForbidden() throws Exception {
        givenStatus("SOQM_HEAD_REVIEW").as(Role.PROCESS_OWNER);
        call("/api/workflow/return-to-operator").andExpect(status().isForbidden());
    }

    @Test
    void returnToOperator_bySoqmTeamFromProcessOwnerReview_isConflict() throws Exception {
        givenStatus("PROCESS_OWNER_REVIEW").as(Role.SOQM_TEAM);
        call("/api/workflow/return-to-operator").andExpect(status().isConflict());
    }

    // ---------- return-to-soqm-lead ----------

    @Test
    void returnToSoqmLead_byControlOperator_isForbidden() throws Exception {
        givenStatus("PROCESS_OWNER_REVIEW").as(Role.CONTROL_OPERATOR);
        call("/api/workflow/return-to-soqm-lead").andExpect(status().isForbidden());
    }

    @Test
    void returnToSoqmLead_onCompletedControl_isConflict() throws Exception {
        givenStatus("COMPLETED").as(Role.PROCESS_OWNER);
        call("/api/workflow/return-to-soqm-lead").andExpect(status().isConflict());
    }

    // ---------- perform-action ----------

    @Test
    void performAction_completeByControlOperator_isForbidden() throws Exception {
        givenStatus("PROCESS_OWNER_REVIEW").as(Role.CONTROL_OPERATOR);
        performAction("COMPLETE").andExpect(status().isForbidden());
    }

    @Test
    void performAction_completeFromReview_isConflict() throws Exception {
        givenStatus("REVIEW").as(Role.PROCESS_OWNER);
        performAction("COMPLETE").andExpect(status().isConflict());
    }

    @Test
    void performAction_unknownAction_isBadRequest() throws Exception {
        givenStatus("REVIEW");
        performAction("SKIP_TO_COMPLETED").andExpect(status().isBadRequest());
    }

    @Test
    void performAction_ownerReturnToFacilitator_movesControlBackToInProgress() throws Exception {
        expectChange = true;
        givenStatus("PROCESS_OWNER_REVIEW").as(Role.PROCESS_OWNER);
        when(performanceService.getPerformanceStatusByControlId(CONTROL_ID)).thenReturn("PROCESS_OWNER_REVIEW");
        when(requiredFieldService.getMissingReviewCommentMessage(control)).thenReturn(Optional.empty());

        performAction("RETURN_TO_FACILITATOR").andExpect(status().isOk());

        assertThat(control.getPerformanceStatus()).isEqualTo("IN_PROGRESS");
    }

    // ---------- legacy approve / return ----------

    @Test
    void approve_withoutActiveStep_isConflict() throws Exception {
        givenStatus("REVIEW");
        when(workflowService.getCurrentStep(CONTROL_ID)).thenReturn(null);

        legacy("/api/workflow/approve").andExpect(status().isConflict());
        verify(workflowService, never()).approveStep(any(), anyString());
    }

    @Test
    void approve_processOwnerStepByFacilitator_isForbidden() throws Exception {
        givenStatus("PROCESS_OWNER_REVIEW").as(Role.FACILITATOR);
        when(workflowService.getCurrentStep(CONTROL_ID)).thenReturn(step(WorkflowStepType.PROCESS_OWNER));

        legacy("/api/workflow/approve").andExpect(status().isForbidden());
        verify(workflowService, never()).approveStep(any(), anyString());
    }

    @Test
    void approve_whenControlStatusDoesNotMatchStep_isConflict() throws Exception {
        givenStatus("PROCESS_OWNER_REVIEW").as(Role.CONTROL_OPERATOR);
        when(workflowService.getCurrentStep(CONTROL_ID)).thenReturn(step(WorkflowStepType.CONTROL_OPERATOR));

        legacy("/api/workflow/approve").andExpect(status().isConflict());
        verify(workflowService, never()).approveStep(any(), anyString());
    }

    @Test
    void return_fromFacilitatorStep_isConflict() throws Exception {
        givenStatus("IN_PROGRESS");
        when(workflowService.getCurrentStep(CONTROL_ID)).thenReturn(step(WorkflowStepType.FACILITATOR));

        legacy("/api/workflow/return").andExpect(status().isConflict());
        verify(workflowService, never()).returnStep(any(), anyString());
    }

    // ---------- helpers ----------

    private enum Role { FACILITATOR, CONTROL_OPERATOR, SOQM_TEAM, PROCESS_OWNER }

    private Given givenStatus(String status) {
        control.setPerformanceStatus(status);
        return new Given();
    }

    private class Given {
        void as(Role role) {
            ControlPermission permission = new ControlPermission(true, true, Set.of(), true, false,
                    false, false,
                    role == Role.FACILITATOR,
                    role == Role.CONTROL_OPERATOR,
                    role == Role.SOQM_TEAM,
                    role == Role.PROCESS_OWNER);
            when(controlPermissionService.resolve(control, currentUser)).thenReturn(permission);
        }
    }

    private ResultActions call(String url) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(url)
                .param("controlId", String.valueOf(CONTROL_ID))
                .sessionAttr("currentUser", currentUser));
    }

    private ResultActions performAction(String action) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/api/workflow/perform-action")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlId\":" + CONTROL_ID + ",\"action\":\"" + action + "\",\"comment\":\"why\"}")
                .sessionAttr("currentUser", currentUser));
    }

    private ResultActions legacy(String url) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post(url)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"controlId\":" + CONTROL_ID + "}")
                .sessionAttr("currentUser", currentUser));
    }

    private WorkflowStepDTO step(WorkflowStepType type) {
        WorkflowStepDTO step = new WorkflowStepDTO();
        step.setControlId(CONTROL_ID);
        step.setStepType(type);
        return step;
    }
}
