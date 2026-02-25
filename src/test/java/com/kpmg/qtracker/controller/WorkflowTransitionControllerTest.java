package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.WorkflowHistoryRepository;
import com.kpmg.qtracker.service.IControlService;
import com.kpmg.qtracker.service.NotificationService;
import com.kpmg.qtracker.service.WorkflowRequiredFieldService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
}
