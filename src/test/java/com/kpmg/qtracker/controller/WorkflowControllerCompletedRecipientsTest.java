package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.entity.WorkflowHistory;
import com.kpmg.qtracker.repository.WorkflowHistoryRepository;
import com.kpmg.qtracker.service.ControlAssignmentService;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
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
                requiredFieldService
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

    private ControlAssignmentDTO completedAssignment() {
        ControlAssignmentDTO dto = new ControlAssignmentDTO();
        dto.setFacilitator(List.of(" fac@kpmg.kz ", "", "fac@kpmg.kz"));
        dto.setControlOperator(List.of("op@kpmg.kz", " "));
        dto.setSoqmLead(List.of("soqm@kpmg.kz"));
        dto.setProcessOwner(List.of("owner@kpmg.kz", "owner2@kpmg.kz"));
        dto.setControlSharedWith(List.of("shared@kpmg.kz"));
        return dto;
    }
}
