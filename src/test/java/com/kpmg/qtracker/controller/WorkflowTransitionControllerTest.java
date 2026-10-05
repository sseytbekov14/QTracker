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
@Import(WorkflowTransitionGuard.class)
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
                eq("CONTROL_OPERATOR"),
                eq("Control Operator"),
                eq("Facilitator"),
                eq("Need fixes in control steps"),
                eq("RETURN_TO_FACILITATOR")
        );
    }

    @Test
    void sharedUser_cannotResubmitACompletedControl() throws Exception {
        User currentUser = new User();
        currentUser.setAccessLevel(AccessLevel.PARTICIPANT);
        currentUser.setId(3L);
        TestUsers.withRole(currentUser, "FACILITATOR");
        currentUser.setMail("shared@kpmg.kz");
        currentUser.setDisplayName("Shared User");

        Control control = new Control();
        control.setId(30L);
        control.setPerformanceStatus("COMPLETED");

        when(controlService.getControlById(30L)).thenReturn(Optional.of(control));
        when(controlPermissionService.resolve(control, currentUser))
                .thenReturn(new ControlPermission(true, true,
                        java.util.Set.of(ControlPermission.FIELD_CONTROL_STEPS_PERFORMED),
                        false, false, true, true, false, false, false));

        mockMvc.perform(post("/api/workflow/shared-submit-to-soqm-lead")
                        .param("controlId", "30")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isForbidden());
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

    @Test
    void sharedSubmit_isForbiddenForEveryone_evenASoqmSharedViewer() throws Exception {
        Control control = controlInStatus(49L, "COMPLETED");
        User sharedSoqm = user("soqm@kpmg.kz");
        givenPermission(control, sharedSoqm, new ControlPermission(true, true, java.util.Set.of(), true, true,
                true, false, false, true, false));

        mockMvc.perform(post("/api/workflow/shared-submit-to-soqm-lead").param("controlId", "49")
                        .sessionAttr("currentUser", sharedSoqm))
                .andExpect(status().isForbidden())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("can only view it")));

        verify(controlService, never()).save(any(Control.class));
    }

    @Test
    void sharedSubmit_byUserWhoIsNotSharedViewer_isForbidden() throws Exception {
        Control control = controlInStatus(50L, "COMPLETED");
        User soqm = user("soqm@kpmg.kz");
        givenPermission(control, soqm, new ControlPermission(true, true, java.util.Set.of(), true, true,
                false, false, false, true, false));

        mockMvc.perform(post("/api/workflow/shared-submit-to-soqm-lead").param("controlId", "50")
                        .sessionAttr("currentUser", soqm))
                .andExpect(status().isForbidden());

        verify(controlService, never()).save(any(Control.class));
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

