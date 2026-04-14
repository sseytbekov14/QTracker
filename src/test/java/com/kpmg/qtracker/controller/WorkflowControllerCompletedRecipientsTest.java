package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.entity.WorkflowHistory;
import com.kpmg.qtracker.repository.WorkflowHistoryRepository;
import com.kpmg.qtracker.service.ControlAssignmentService;
import com.kpmg.qtracker.service.ControlPermission;
import com.kpmg.qtracker.service.ControlPermissionService;
import com.kpmg.qtracker.service.ControlService;
import com.kpmg.qtracker.service.IPerformanceService;
import com.kpmg.qtracker.service.NotificationService;
import com.kpmg.qtracker.service.NotificationTemplateService;
import com.kpmg.qtracker.service.WorkflowRequiredFieldService;
import com.kpmg.qtracker.service.WorkflowService;
import jakarta.servlet.http.HttpSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WorkflowControllerCompletedRecipientsTest {

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
    @Mock
    private HttpSession session;

    private WorkflowController controller;

    @BeforeEach
    void setUp() {
        controller = new WorkflowController(
                workflowService,
                performanceService,
                controlService,
                controlAssignmentService,
                workflowHistoryRepository,
                notificationService,
                requiredFieldService,
                controlPermissionService
        );
    }

    @Test
    void completeControl_sendsCompletedOnlyToFacilitatorOperatorSoqmLead() {
        Long controlId = 100L;
        Control control = new Control();
        control.setId(controlId);
        control.setControlId("CTRL-100");
        control.setPerformanceStatus("PROCESS_OWNER_REVIEW");

        User currentUser = new User();
        currentUser.setId(1L);
        currentUser.setMail("owner.current@kpmg.kz");
        currentUser.setDisplayName("Current Owner");
        currentUser.setRole("PROCESS_OWNER");

        when(session.getAttribute("currentUser")).thenReturn(currentUser);
        when(controlService.getControlById(controlId)).thenReturn(Optional.of(control));
        when(controlPermissionService.resolve(control, currentUser))
                .thenReturn(new ControlPermission(true, true, java.util.Set.of(), true, false,
                        false, false, false, false, false, true));
        when(controlService.save(any(Control.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(workflowHistoryRepository.save(any(WorkflowHistory.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(controlAssignmentService.getAssignmentByControlId(controlId)).thenReturn(completedAssignment());

        ResponseEntity<?> response = controller.completeControl(controlId, session);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(control.getPerformanceStatus()).isEqualTo("COMPLETED");

        ArgumentCaptor<List<String>> recipientsCaptor = ArgumentCaptor.forClass(List.class);
        verify(notificationService).sendTemplateNotifications(
                eq(control),
                recipientsCaptor.capture(),
                eq(NotificationTemplateService.TemplateType.COMPLETED_ALL),
                eq(false)
        );

        List<String> recipients = recipientsCaptor.getValue();
        assertThat(recipients).containsExactlyInAnyOrder("fac@kpmg.kz", "op@kpmg.kz", "soqm@kpmg.kz");
        assertThat(recipients).doesNotContain("owner@kpmg.kz", "owner2@kpmg.kz");
    }

    @Test
    void completeActionNotificationFlow_excludesProcessOwnerRecipients() throws Exception {
        Long controlId = 101L;
        Control control = new Control();
        control.setId(controlId);
        control.setControlId("CTRL-101");

        when(controlAssignmentService.getAssignmentByControlId(controlId)).thenReturn(completedAssignment());

        Method sendWorkflowNotifications = WorkflowController.class.getDeclaredMethod(
                "sendWorkflowNotifications",
                Control.class,
                String.class,
                String.class,
                String.class
        );
        sendWorkflowNotifications.setAccessible(true);
        sendWorkflowNotifications.invoke(controller, control, "COMPLETE", "PROCESS_OWNER_REVIEW", "COMPLETED");

        ArgumentCaptor<List<String>> recipientsCaptor = ArgumentCaptor.forClass(List.class);
        verify(notificationService).sendTemplateNotifications(
                eq(control),
                recipientsCaptor.capture(),
                eq(NotificationTemplateService.TemplateType.COMPLETED_ALL),
                eq(false)
        );

        List<String> recipients = recipientsCaptor.getValue();
        assertThat(recipients).containsExactlyInAnyOrder("fac@kpmg.kz", "op@kpmg.kz", "soqm@kpmg.kz");
        assertThat(recipients).doesNotContain("owner@kpmg.kz", "owner2@kpmg.kz");
    }

    @Test
    void returnToOperator_includesCommentInReturnNotification() {
        Long controlId = 200L;
        String comment = "Please update evidence references";

        Control control = new Control();
        control.setId(controlId);
        control.setControlId("CTRL-200");
        control.setPerformanceStatus("SOQM_HEAD_REVIEW");

        User currentUser = new User();
        currentUser.setId(2L);
        currentUser.setMail("soqm.current@kpmg.kz");
        currentUser.setDisplayName("SoQM Reviewer");
        currentUser.setRole("SOQM_TEAM");

        when(session.getAttribute("currentUser")).thenReturn(currentUser);
        when(controlService.getControlById(controlId)).thenReturn(Optional.of(control));
        when(controlPermissionService.resolve(control, currentUser))
                .thenReturn(new ControlPermission(true, true, java.util.Set.of(), true, false,
                        false, false, false, false, true, false));
        when(controlService.save(any(Control.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(workflowHistoryRepository.save(any(WorkflowHistory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ControlAssignmentDTO assignment = new ControlAssignmentDTO();
        assignment.setControlOperator(List.of("operator@kpmg.kz"));
        when(controlAssignmentService.getAssignmentByControlId(controlId)).thenReturn(assignment);

        ResponseEntity<?> response = controller.returnToOperator(controlId, comment, session);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(control.getReturnToOperatorComment()).isEqualTo(comment);

        verify(notificationService).sendReturnNotifications(
                eq(control),
                eq(List.of("operator@kpmg.kz")),
                eq("SOQM_TEAM"),
                eq("SoQM Reviewer"),
                eq("Control Operator"),
                eq(comment),
                eq("RETURN_TO_OPERATOR")
        );
        verify(notificationService, never()).sendTemplateNotifications(
                eq(control),
                anyList(),
                eq(NotificationTemplateService.TemplateType.SOQM_TO_OPERATOR_RETURN),
                eq(false)
        );
    }

    @Test
    void returnToSoqmLead_includesCommentInReturnNotification() {
        Long controlId = 201L;
        String comment = "Please re-check risk owner mapping";

        Control control = new Control();
        control.setId(controlId);
        control.setControlId("CTRL-201");
        control.setPerformanceStatus("PROCESS_OWNER_REVIEW");

        User currentUser = new User();
        currentUser.setId(3L);
        currentUser.setMail("owner.current@kpmg.kz");
        currentUser.setDisplayName("Process Owner");
        currentUser.setRole("PROCESS_OWNER");

        when(session.getAttribute("currentUser")).thenReturn(currentUser);
        when(controlService.getControlById(controlId)).thenReturn(Optional.of(control));
        when(controlPermissionService.resolve(control, currentUser))
                .thenReturn(new ControlPermission(true, true, java.util.Set.of(), true, false,
                        false, false, false, false, false, true));
        when(controlService.save(any(Control.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(workflowHistoryRepository.save(any(WorkflowHistory.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ControlAssignmentDTO assignment = new ControlAssignmentDTO();
        assignment.setSoqmLead(List.of("soqm@kpmg.kz"));
        when(controlAssignmentService.getAssignmentByControlId(controlId)).thenReturn(assignment);

        ResponseEntity<?> response = controller.returnToSoqmLead(controlId, comment, session);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(control.getReturnToSoqmTeamComment()).isEqualTo(comment);

        verify(notificationService).sendReturnNotifications(
                eq(control),
                eq(List.of("soqm@kpmg.kz")),
                eq("PROCESS_OWNER"),
                eq("Process Owner"),
                eq("SoQM Team"),
                eq(comment),
                eq("RETURN_TO_SOQM_TEAM")
        );
    }

    private ControlAssignmentDTO completedAssignment() {
        ControlAssignmentDTO dto = new ControlAssignmentDTO();
        dto.setFacilitator(List.of(" fac@kpmg.kz ", "", "fac@kpmg.kz"));
        dto.setControlOperator(List.of("op@kpmg.kz", " "));
        dto.setSoqmLead(List.of("soqm@kpmg.kz"));
        dto.setProcessOwner(List.of("owner@kpmg.kz", "owner2@kpmg.kz"));
        dto.setControlSharedWith(List.of("shared@kpmg.kz"));
        return dto;
    }

    @Test
    void sharedCompletedUser_cannotInvokeWorkflowActionInWorkflowController() {
        Long controlId = 300L;
        Control control = new Control();
        control.setId(controlId);
        control.setControlId("CTRL-300");
        control.setPerformanceStatus("COMPLETED");

        User currentUser = new User();
        currentUser.setMail("shared@kpmg.kz");
        currentUser.setDisplayName("Shared User");
        currentUser.setRole("FACILITATOR");

        when(session.getAttribute("currentUser")).thenReturn(currentUser);
        when(controlService.getControlById(controlId)).thenReturn(Optional.of(control));
        when(controlPermissionService.resolve(control, currentUser))
                .thenReturn(new ControlPermission(true, true,
                        java.util.Set.of(ControlPermission.FIELD_CONTROL_STEPS_PERFORMED),
                        false, false, true, true, true, false, false, false));

        ResponseEntity<?> response = controller.completeControl(controlId, session);

        assertThat(response.getStatusCode().value()).isEqualTo(403);
        verify(notificationService, never()).sendTemplateNotifications(
                eq(control),
                anyList(),
                eq(NotificationTemplateService.TemplateType.COMPLETED_ALL),
                eq(false)
        );
    }
}
