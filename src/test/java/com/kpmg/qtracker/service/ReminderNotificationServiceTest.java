package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.dto.ControlDetailsDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.repository.ControlNotificationLogRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReminderNotificationServiceTest {

    @Mock
    private ControlRepository controlRepository;

    @Mock
    private ControlAssignmentService controlAssignmentService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private ControlDetailsService controlDetailsService;

    @Mock
    private ControlNotificationLogRepository logRepository;

    @InjectMocks
    private ReminderNotificationService service;

    @Test
    void sendsReminder1OnMonthlyDay3ForFacilitatorStage() {
        Control control = new Control();
        control.setId(10L);
        control.setControlFrequency("Monthly");
        control.setControlStatus("Facilitator Review");

        ControlAssignmentDTO assignment = new ControlAssignmentDTO();
        assignment.setControlOperationDate(LocalDate.of(2026, 2, 4));
        assignment.setFacilitator(List.of("facilitator@kpmg.kz"));

        LocalDate today = LocalDate.of(2026, 2, 7); // day 3

        when(logRepository.existsByControlIdAndNotificationCodeAndScheduledDate(10L, "REMINDER_1", today))
                .thenReturn(false);
        when(controlDetailsService.getDetailsByControlId(10L)).thenReturn(new ControlDetailsDTO());

        service.processControl(control, assignment, today);

        ArgumentCaptor<List<String>> recipients = ArgumentCaptor.forClass(List.class);
        verify(notificationService).sendTemplateNotifications(
                eq(control),
                recipients.capture(),
                eq(NotificationTemplateService.TemplateType.REMINDER_1_OPEN),
                eq(false)
        );
        assertThat(recipients.getValue()).containsExactly("facilitator@kpmg.kz");
    }

    @Test
    void sendsForwardReminderWhenResponseExists() {
        Control control = new Control();
        control.setId(13L);
        control.setControlFrequency("Monthly");
        control.setControlStatus("Facilitator Review");

        ControlAssignmentDTO assignment = new ControlAssignmentDTO();
        assignment.setControlOperationDate(LocalDate.of(2026, 2, 4));
        assignment.setFacilitator(List.of("facilitator@kpmg.kz"));

        ControlDetailsDTO details = new ControlDetailsDTO();
        details.setControlStepsPerformed("Filled");

        LocalDate today = LocalDate.of(2026, 2, 7); // day 3

        when(logRepository.existsByControlIdAndNotificationCodeAndScheduledDate(13L, "REMINDER_1", today))
                .thenReturn(false);
        when(controlDetailsService.getDetailsByControlId(13L)).thenReturn(details);

        service.processControl(control, assignment, today);

        verify(notificationService).sendTemplateNotifications(
                eq(control),
                eq(List.of("facilitator@kpmg.kz")),
                eq(NotificationTemplateService.TemplateType.REMINDER_1_FORWARD),
                eq(false)
        );
    }

    @Test
    void sendsReminder2OpenWhenNoResponseExists() {
        Control control = new Control();
        control.setId(14L);
        control.setControlFrequency("Monthly");
        control.setControlStatus("Facilitator Review");

        ControlAssignmentDTO assignment = new ControlAssignmentDTO();
        assignment.setControlOperationDate(LocalDate.of(2026, 2, 4));
        assignment.setFacilitator(List.of("facilitator@kpmg.kz"));

        LocalDate today = LocalDate.of(2026, 2, 10); // day 6

        when(logRepository.existsByControlIdAndNotificationCodeAndScheduledDate(14L, "REMINDER_2", today))
                .thenReturn(false);
        when(controlDetailsService.getDetailsByControlId(14L)).thenReturn(new ControlDetailsDTO());

        service.processControl(control, assignment, today);

        verify(notificationService).sendTemplateNotifications(
                eq(control),
                eq(List.of("facilitator@kpmg.kz")),
                eq(NotificationTemplateService.TemplateType.REMINDER_2_OPEN),
                eq(false)
        );
    }

    @Test
    void sendsReminder2ForwardWhenResponseExists() {
        Control control = new Control();
        control.setId(15L);
        control.setControlFrequency("Monthly");
        control.setControlStatus("Facilitator Review");

        ControlAssignmentDTO assignment = new ControlAssignmentDTO();
        assignment.setControlOperationDate(LocalDate.of(2026, 2, 4));
        assignment.setFacilitator(List.of("facilitator@kpmg.kz"));

        ControlDetailsDTO details = new ControlDetailsDTO();
        details.setControlStepsPerformed("Done");

        LocalDate today = LocalDate.of(2026, 2, 10); // day 6

        when(logRepository.existsByControlIdAndNotificationCodeAndScheduledDate(15L, "REMINDER_2", today))
                .thenReturn(false);
        when(controlDetailsService.getDetailsByControlId(15L)).thenReturn(details);

        service.processControl(control, assignment, today);

        verify(notificationService).sendTemplateNotifications(
                eq(control),
                eq(List.of("facilitator@kpmg.kz")),
                eq(NotificationTemplateService.TemplateType.REMINDER_2_FORWARD),
                eq(false)
        );
    }

    @Test
    void skipsSendWhenLogExists() {
        Control control = new Control();
        control.setId(11L);
        control.setControlFrequency("Monthly");
        control.setControlStatus("Facilitator Review");

        ControlAssignmentDTO assignment = new ControlAssignmentDTO();
        assignment.setControlOperationDate(LocalDate.of(2026, 2, 1));
        assignment.setFacilitator(List.of("facilitator@kpmg.kz"));

        LocalDate today = LocalDate.of(2026, 2, 1); // day 0

        when(logRepository.existsByControlIdAndNotificationCodeAndScheduledDate(11L, "START", today))
                .thenReturn(true);

        service.processControl(control, assignment, today);

        verify(notificationService, never()).sendTemplateNotifications(any(), any(), any(), anyBoolean());
    }

    @Test
    void sendsOverdueOnSecondWorkingDay() {
        Control control = new Control();
        control.setId(12L);
        control.setControlFrequency("Monthly");
        control.setControlStatus("Control Operator Review");

        ControlAssignmentDTO assignment = new ControlAssignmentDTO();
        assignment.setControlOperationDate(LocalDate.of(2026, 1, 1));
        assignment.setControlOperationDeadline(LocalDate.of(2026, 2, 1)); // Sunday
        assignment.setControlOperator(List.of("operator@kpmg.kz"));

        LocalDate today = LocalDate.of(2026, 2, 3); // Tuesday, 2nd working day after overdue starts

        when(logRepository.existsByControlIdAndNotificationCodeAndScheduledDate(12L, "OVERDUE", today))
                .thenReturn(false);

        service.processControl(control, assignment, today);

        verify(notificationService).sendTemplateNotifications(
                eq(control),
                eq(List.of("operator@kpmg.kz")),
                eq(NotificationTemplateService.TemplateType.DEADLINE),
                eq(false)
        );
    }

    @Test
    void overdueWorkingDays_sendsOnEveryOtherWorkingDayUntilLimit() {
        ReflectionTestUtils.setField(service, "useWorkingDays", true);

        Control control = new Control();
        control.setId(21L);
        control.setControlStatus("Control Operator Review");

        ControlAssignmentDTO assignment = new ControlAssignmentDTO();
        assignment.setControlOperationDeadline(LocalDate.of(2026, 2, 10));
        assignment.setControlOperator(List.of("operator@kpmg.kz"));

        LocalDate[] dates = {
                LocalDate.of(2026, 2, 12), // working day #2 -> send
                LocalDate.of(2026, 2, 13), // #3 -> no send
                LocalDate.of(2026, 2, 14), // weekend -> no send
                LocalDate.of(2026, 2, 16), // #4 -> send
                LocalDate.of(2026, 2, 18), // #6 -> send
                LocalDate.of(2026, 2, 19)  // #7 -> stop
        };

        for (LocalDate today : dates) {
            service.processControl(control, assignment, today);
        }

        ArgumentCaptor<List<String>> recipients = ArgumentCaptor.forClass(List.class);
        verify(notificationService, times(3)).sendTemplateNotifications(
                eq(control),
                recipients.capture(),
                eq(NotificationTemplateService.TemplateType.DEADLINE),
                eq(false)
        );
        recipients.getAllValues().forEach(list -> assertThat(list).containsExactly("operator@kpmg.kz"));
    }

    @Test
    void overdueCalendarDays_sendsOnEveryOtherCalendarDayUntilLimit() {
        ReflectionTestUtils.setField(service, "useWorkingDays", false);

        Control control = new Control();
        control.setId(22L);
        control.setControlStatus("Control Operator Review");

        ControlAssignmentDTO assignment = new ControlAssignmentDTO();
        assignment.setControlOperationDeadline(LocalDate.of(2026, 2, 10));
        assignment.setControlOperator(List.of("operator@kpmg.kz"));

        LocalDate[] dates = {
                LocalDate.of(2026, 2, 12), // day 2 -> send
                LocalDate.of(2026, 2, 14), // day 4 -> send (calendar day)
                LocalDate.of(2026, 2, 16), // day 6 -> send
                LocalDate.of(2026, 2, 17)  // day 7 -> stop
        };

        for (LocalDate today : dates) {
            service.processControl(control, assignment, today);
        }

        ArgumentCaptor<List<String>> recipients = ArgumentCaptor.forClass(List.class);
        verify(notificationService, times(3)).sendTemplateNotifications(
                eq(control),
                recipients.capture(),
                eq(NotificationTemplateService.TemplateType.DEADLINE),
                eq(false)
        );
        recipients.getAllValues().forEach(list -> assertThat(list).containsExactly("operator@kpmg.kz"));
    }

    @Test
    void overdueIdempotencyPreventsDuplicateSend() {
        ReflectionTestUtils.setField(service, "useWorkingDays", true);

        Control control = new Control();
        control.setId(23L);
        control.setControlStatus("Control Operator Review");

        ControlAssignmentDTO assignment = new ControlAssignmentDTO();
        assignment.setControlOperationDeadline(LocalDate.of(2026, 2, 10));
        assignment.setControlOperator(List.of("operator@kpmg.kz"));

        LocalDate today = LocalDate.of(2026, 2, 12);

        when(logRepository.existsByControlIdAndNotificationCodeAndScheduledDate(23L, "OVERDUE", today))
                .thenReturn(true);

        service.processControl(control, assignment, today);

        verify(notificationService, never()).sendTemplateNotifications(any(), any(), any(), anyBoolean());
        verify(logRepository, never()).save(any());
    }
}
