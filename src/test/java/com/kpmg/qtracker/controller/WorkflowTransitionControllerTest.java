package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.WorkflowHistoryRepository;
import com.kpmg.qtracker.service.ControlPermission;
import com.kpmg.qtracker.service.ControlPermissionService;
import com.kpmg.qtracker.service.IControlService;
import com.kpmg.qtracker.service.NotificationService;
import com.kpmg.qtracker.service.WorkflowRequiredFieldService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
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
    void initiateControl_doesNotSendImmediateNotifications() throws Exception {
        User currentUser = new User();
        currentUser.setId(1L);
        currentUser.setRole("SOQM_LEAD");
        currentUser.setMail("soqm@kpmg.kz");

        Control control = new Control();
        control.setId(10L);
        control.setControlStatus("DRAFT");

        ControlAssignment assignment = new ControlAssignment();
        assignment.setControlId(10L);
        assignment.setFacilitator("fac@example.test");
        assignment.setControlOperator("op@example.test");

        when(controlService.getControlById(10L)).thenReturn(Optional.of(control));
        when(controlPermissionService.resolve(control, currentUser))
                .thenReturn(new ControlPermission(true, true, java.util.Set.of(), true, true,
                        false, false, false, false, true, false));
        when(controlAssignmentRepository.findByControlId(10L)).thenReturn(Optional.of(assignment));
        when(controlService.save(any(Control.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(requiredFieldService.getMissingFieldMessage(any(Control.class), any(User.class)))
                .thenReturn(Optional.empty());

        mockMvc.perform(post("/api/workflow/initiate")
                        .param("controlId", "10")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isOk());

        verify(notificationService, never()).sendInitiateNotifications(eq(control), anyList());
    }

    @Test
    void returnToFacilitator_includesCommentInReturnNotification() throws Exception {
        User currentUser = new User();
        currentUser.setId(2L);
        currentUser.setRole("CONTROL_OPERATOR");
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
                        false, false, false, true, false, false));
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
    void sharedCompletedUser_cannotInvokeWorkflowAction() throws Exception {
        User currentUser = new User();
        currentUser.setId(3L);
        currentUser.setRole("FACILITATOR");
        currentUser.setMail("shared@kpmg.kz");
        currentUser.setDisplayName("Shared User");

        Control control = new Control();
        control.setId(30L);
        control.setPerformanceStatus("COMPLETED");

        when(controlService.getControlById(30L)).thenReturn(Optional.of(control));
        when(controlPermissionService.resolve(control, currentUser))
                .thenReturn(new ControlPermission(true, true,
                        java.util.Set.of(ControlPermission.FIELD_CONTROL_STEPS_PERFORMED),
                        false, false, true, true, true, false, false, false));

        mockMvc.perform(post("/api/workflow/shared-submit-to-soqm-lead")
                        .param("controlId", "30")
                        .sessionAttr("currentUser", currentUser))
                .andExpect(status().isForbidden());
    }
}

