package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
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

        currentUser.setAccessLevel(AccessLevel.PARTICIPANT);
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
    void returnToOperator_byFacilitator_isForbidden() throws Exception {
        givenStatus("SOQM_HEAD_REVIEW").as(Role.FACILITATOR);
        call("/api/workflow/return-to-operator").andExpect(status().isForbidden());
    }

    @Test
    void returnToOperator_byProcessOwnerBeforeTheirStep_isConflict() throws Exception {
        givenStatus("SOQM_HEAD_REVIEW").as(Role.PROCESS_OWNER);
        call("/api/workflow/return-to-operator").andExpect(status().isConflict());
    }

    @Test
    void returnToOperator_bySoqmTeamFromProcessOwnerReview_isConflict() throws Exception {
        givenStatus("PROCESS_OWNER_REVIEW").as(Role.SOQM_TEAM);
        call("/api/workflow/return-to-operator").andExpect(status().isConflict());
    }

    // ---------- the Process Owner's return to the Control Operator ----------

    @Test
    void ownerReturnToOperator_byControlOperator_isForbidden() throws Exception {
        givenStatus("PROCESS_OWNER_REVIEW").as(Role.CONTROL_OPERATOR);
        call("/api/workflow/return-to-operator").andExpect(status().isForbidden());
    }

    @Test
    void ownerReturnToOperator_onCompletedControl_isConflict() throws Exception {
        givenStatus("COMPLETED").as(Role.PROCESS_OWNER);
        call("/api/workflow/return-to-operator").andExpect(status().isConflict());
    }

    @Test
    void ownerReturnToOperator_withoutComment_isBadRequest() throws Exception {
        givenStatus("PROCESS_OWNER_REVIEW").as(Role.PROCESS_OWNER);
        when(requiredFieldService.getMissingReviewCommentMessage(control)).thenReturn(Optional.empty());

        call("/api/workflow/return-to-operator")
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string("A comment is required to return the control"));
        mockMvc.perform(MockMvcRequestBuilders.post("/api/workflow/return-to-operator")
                        .param("controlId", String.valueOf(CONTROL_ID))
                        .param("comments", "   ")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isBadRequest());
    }

    @Test
    void returnToSoqmLead_isGone() throws Exception {
        call("/api/workflow/return-to-soqm-lead").andExpect(status().isNotFound());
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
    void performAction_processOwnerCannotReturnToFacilitator() throws Exception {
        givenStatus("PROCESS_OWNER_REVIEW").as(Role.PROCESS_OWNER);
        performAction("RETURN_TO_FACILITATOR").andExpect(status().isForbidden());
        performAction("RETURN_TO_SOQM_TEAM").andExpect(status().isBadRequest());
        performAction("REJECT").andExpect(status().isBadRequest());
    }

    @Test
    void performAction_sendForRevision_returnsToTheOperator_asAReturn() throws Exception {
        expectChange = true;
        givenStatus("PROCESS_OWNER_REVIEW").as(Role.PROCESS_OWNER);
        when(performanceService.getPerformanceStatusByControlId(CONTROL_ID)).thenReturn("PROCESS_OWNER_REVIEW");
        when(requiredFieldService.getMissingReviewCommentMessage(control)).thenReturn(Optional.empty());

        performAction("SEND_FOR_REVISION").andExpect(status().isOk());

        assertThat(control.getPerformanceStatus()).isEqualTo("REVIEW");
        assertThat(control.getReturnToOperatorComment()).isEqualTo("why");
        org.mockito.ArgumentCaptor<com.kpmg.qtracker.entity.WorkflowHistory> history =
                org.mockito.ArgumentCaptor.forClass(com.kpmg.qtracker.entity.WorkflowHistory.class);
        verify(workflowHistoryRepository).save(history.capture());
        assertThat(history.getValue().getActionType())
                .isEqualTo(com.kpmg.qtracker.enums.WorkflowActionType.RETURN_TO_OPERATOR);
    }

    @Test
    void performAction_returnWithoutComment_isBadRequest() throws Exception {
        givenStatus("REVIEW").as(Role.CONTROL_OPERATOR);
        when(requiredFieldService.getMissingReviewCommentMessage(control)).thenReturn(Optional.empty());

        mockMvc.perform(MockMvcRequestBuilders.post("/api/workflow/perform-action")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"controlId\":" + CONTROL_ID + ",\"action\":\"RETURN_TO_FACILITATOR\"}")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isBadRequest());
    }

    // ---------- helpers ----------

    private enum Role { FACILITATOR, CONTROL_OPERATOR, SOQM_TEAM, PROCESS_OWNER }

    private Given givenStatus(String status) {
        control.setPerformanceStatus(status);
        return new Given();
    }

    private class Given {
        void as(Role role) {
            // Any SoQM user may perform the SoQM steps: the policy gives them canEditAll
            ControlPermission permission = new ControlPermission(true, true, Set.of(), true, role == Role.SOQM_TEAM,
                    false,
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
}
