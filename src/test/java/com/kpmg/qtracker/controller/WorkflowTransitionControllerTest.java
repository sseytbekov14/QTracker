package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.support.TestUsers;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.WorkflowHistoryRepository;
import com.kpmg.qtracker.service.ControlPermission;
import com.kpmg.qtracker.service.ControlPermissionService;
import com.kpmg.qtracker.service.IControlService;
import com.kpmg.qtracker.service.NotificationService;
import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.service.AdminAuditService;
import com.kpmg.qtracker.service.ControlAssignmentService;
import com.kpmg.qtracker.service.WorkflowMoveService;
import com.kpmg.qtracker.service.WorkflowRequiredFieldService;
import com.kpmg.qtracker.service.WorkflowTransitionGuard;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = WorkflowTransitionController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({WorkflowTransitionGuard.class, WorkflowMoveService.class})
class WorkflowTransitionControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private IControlService controlService;

    @MockBean
    private ControlAssignmentRepository controlAssignmentRepository;

    @MockBean
    private WorkflowHistoryRepository workflowHistoryRepository;

    @MockBean
    private NotificationService notificationService;

    @MockBean
    private WorkflowRequiredFieldService requiredFieldService;

    @MockBean
    private ControlPermissionService controlPermissionService;

    @MockBean
    private ControlAssignmentService controlAssignmentService;

    @MockBean
    private AdminAuditService adminAuditService;

    @Test
    void returnToFacilitator_includesCommentInReturnNotification() throws Exception {
        User currentUser = new User();
        currentUser.setAccessLevel(AccessLevel.PARTICIPANT);
        currentUser.setId(2L);
        TestUsers.withRole(currentUser, "CONTROL_OPERATOR");
        currentUser.setMail("operator@kpmg.kz");
        currentUser.setDisplayName("Control Operator");

        Control control = new Control();
        control.setId(20L);
        control.setControlStatus("REVIEW");

        ControlAssignment assignment = new ControlAssignment();
        assignment.setControlId(20L);
        assignment.setControlOperator("operator@kpmg.kz");
        assignment.setFacilitator("facilitator@kpmg.kz");

        when(controlService.getControlById(20L)).thenReturn(Optional.of(control));
        when(controlPermissionService.resolve(control, currentUser))
                .thenReturn(new ControlPermission(true, true, java.util.Set.of(), true, false,
                        false, false, true, false, false));
        when(controlAssignmentRepository.findByControlId(20L)).thenReturn(Optional.of(assignment));
        ControlAssignmentDTO assignmentDto = new ControlAssignmentDTO();
        assignmentDto.setControlOperator(java.util.List.of("operator@kpmg.kz"));
        assignmentDto.setFacilitator(java.util.List.of("facilitator@kpmg.kz"));
        when(controlAssignmentService.getAssignmentByControlId(20L)).thenReturn(assignmentDto);
        when(controlService.save(any(Control.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(workflowHistoryRepository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        mockMvc.perform(post("/api/workflow/return-to-facilitator")
                        .param("controlId", "20")
                        .param("comments", "Need fixes in control steps")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk());

        verify(notificationService).sendReturnNotifications(
                eq(control),
                eq(java.util.List.of("facilitator@kpmg.kz")),
                eq("Control Operator"),
                eq("Control Operator"),
                eq("Facilitator"),
                eq("Need fixes in control steps"),
                eq("RETURN_TO_FACILITATOR")
        );
    }

    @Test
    void sharedSubmit_isGone_soqmReturnsACompletedControlThroughMove() throws Exception {
        mockMvc.perform(post("/api/workflow/shared-submit-to-soqm-lead")
                        .param("controlId", "30")
                        .sessionAttr("currentUser", user("shared@kpmg.kz")))
                .andExpect(status().isNotFound());
    }

    @Test
    void returnToFacilitator_withoutComment_isBadRequest_andChangesNothing() throws Exception {
        Control control = controlInStatus(46L, "REVIEW");
        User operator = user("operator@kpmg.kz");
        givenPermission(control, operator, participant(false, true, false, false));
        when(controlAssignmentRepository.findByControlId(46L)).thenReturn(Optional.of(new ControlAssignment()));

        mockMvc.perform(post("/api/workflow/return-to-facilitator").param("controlId", "46")
                        .sessionAttr("currentUser", operator))
                .andExpect(status().isBadRequest())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("A comment is required to return the control")));

        verify(controlService, never()).save(any(Control.class));
        verify(workflowHistoryRepository, never()).save(any());
    }

    @Test
    void submitToControlOperator_byControlOperator_isForbidden() throws Exception {
        Control control = controlInStatus(42L, "IN_PROGRESS");
        User operator = user("operator@kpmg.kz");
        givenPermission(control, operator, participant(false, true, false, false));

        mockMvc.perform(post("/api/workflow/submit-to-control-operator").param("controlId", "42")
                        .sessionAttr("currentUser", operator))
                .andExpect(status().isForbidden());

        verify(controlService, never()).save(any(Control.class));
    }

    @Test
    void submitToControlOperator_whenAlreadyInReview_isConflict() throws Exception {
        Control control = controlInStatus(43L, "REVIEW");
        User facilitator = user("fac@kpmg.kz");
        givenPermission(control, facilitator, participant(true, false, false, false));

        mockMvc.perform(post("/api/workflow/submit-to-control-operator").param("controlId", "43")
                        .sessionAttr("currentUser", facilitator))
                .andExpect(status().isConflict());

        verify(controlService, never()).save(any(Control.class));
        verify(workflowHistoryRepository, never()).save(any());
    }

    @Test
    void submitToSoqmLead_byFacilitator_isForbidden() throws Exception {
        Control control = controlInStatus(44L, "REVIEW");
        User facilitator = user("fac@kpmg.kz");
        givenPermission(control, facilitator, participant(true, false, false, false));

        mockMvc.perform(post("/api/workflow/submit-to-soqm-lead").param("controlId", "44")
                        .sessionAttr("currentUser", facilitator))
                .andExpect(status().isForbidden());

        verify(controlService, never()).save(any(Control.class));
    }

    @Test
    void submitToSoqmLead_byOperatorWhileFacilitatorStillWorking_isConflict() throws Exception {
        Control control = controlInStatus(45L, "IN_PROGRESS");
        User operator = user("operator@kpmg.kz");
        givenPermission(control, operator, participant(false, true, false, false));

        mockMvc.perform(post("/api/workflow/submit-to-soqm-lead").param("controlId", "45")
                        .sessionAttr("currentUser", operator))
                .andExpect(status().isConflict());

        verify(controlService, never()).save(any(Control.class));
    }

    @Test
    void returnToFacilitator_byFacilitator_isForbidden() throws Exception {
        Control control = controlInStatus(47L, "REVIEW");
        User facilitator = user("fac@kpmg.kz");
        givenPermission(control, facilitator, participant(true, false, false, false));

        mockMvc.perform(post("/api/workflow/return-to-facilitator").param("controlId", "47")
                        .sessionAttr("currentUser", facilitator))
                .andExpect(status().isForbidden());

        verify(controlService, never()).save(any(Control.class));
    }

    @Test
    void returnToFacilitator_afterControlMovedToSoqm_isConflict() throws Exception {
        Control control = controlInStatus(48L, "SOQM_HEAD_REVIEW");
        User operator = user("operator@kpmg.kz");
        givenPermission(control, operator, participant(false, true, false, false));

        mockMvc.perform(post("/api/workflow/return-to-facilitator").param("controlId", "48")
                        .sessionAttr("currentUser", operator))
                .andExpect(status().isConflict());

        verify(controlService, never()).save(any(Control.class));
        verify(notificationService, never())
                .sendReturnNotifications(any(), anyList(), any(), any(), any(), any(), any());
    }

    private Control controlInStatus(Long id, String status) {
        Control control = new Control();
        control.setId(id);
        control.setPerformanceStatus(status);
        when(controlService.getControlById(id)).thenReturn(Optional.of(control));
        return control;
    }

    private User user(String mail) {
        User user = new User();
        user.setAccessLevel(AccessLevel.PARTICIPANT);
        user.setMail(mail);
        user.setDisplayName(mail);
        return user;
    }

    private void givenPermission(Control control, User user, ControlPermission permission) {
        when(controlPermissionService.resolve(control, user)).thenReturn(permission);
    }

    private ControlPermission participant(boolean facilitator, boolean operator, boolean soqm, boolean owner) {
        return new ControlPermission(true, true, java.util.Set.of(), true, false,
                false, facilitator, operator, soqm, owner);
    }
}

