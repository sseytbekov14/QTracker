package com.kpmg.qtracker.scheduler;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlNotificationLog;
import com.kpmg.qtracker.repository.ControlNotificationLogRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.ReminderControlProjection;
import com.kpmg.qtracker.service.ControlAssignmentService;
import com.kpmg.qtracker.service.ControlDetailsService;
import com.kpmg.qtracker.service.NotificationService;
import com.kpmg.qtracker.service.NotificationTemplateService;
import com.kpmg.qtracker.service.ReminderNotificationService;
import com.kpmg.qtracker.service.WorkingDaysService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ControlReminderSchedulerEnabledTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 2, 6);

    @Mock
    private ControlRepository controlRepository;

    @Mock
    private ControlAssignmentService controlAssignmentService;

    @Mock
    private ControlDetailsService controlDetailsService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private ControlNotificationLogRepository logRepository;

    @Spy
    private WorkingDaysService workingDaysService = new WorkingDaysService();

    private ReminderNotificationService reminderNotificationService;
    private ControlReminderScheduler scheduler;

    @BeforeEach
    void setUp() {
        reminderNotificationService = new ReminderNotificationService(
                controlRepository,
                controlAssignmentService,
                controlDetailsService,
                notificationService,
                logRepository,
                workingDaysService
        );
        Clock fixedClock = Clock.fixed(Instant.parse("2026-02-06T00:00:00Z"), ZoneId.of("Asia/Almaty"));
        scheduler = new ControlReminderScheduler(reminderNotificationService, fixedClock);
    }

    @Test
    void runDailyIsIdempotentForSameDay() {
        Control control = new Control();
        control.setId(200L);
        control.setControlFrequency("Monthly");
        control.setControlStatus("IN_PROGRESS");
        control.setDeadline(TODAY.plusDays(30));

        ControlAssignmentDTO assignment = new ControlAssignmentDTO();
        assignment.setControlOperationDate(workingDaysService.addWorkingDays(TODAY, -3));
        assignment.setControlOperationDeadline(TODAY.plusDays(30));
        assignment.setFacilitator(List.of("facilitator@kpmg.kz"));
        assignment.setControlOperator(List.of("operator@kpmg.kz"));

        ReminderControlProjection row = projectionFor(control, assignment);
        when(controlRepository.findAllForReminders()).thenReturn(List.of(row));
        when(logRepository.existsByControlIdAndNotificationCodeAndScheduledDate(200L, "REMINDER_1", TODAY))
                .thenReturn(false, true);
        List<ControlNotificationLog> savedLogs = new ArrayList<>();
        doAnswer(invocation -> {
            ControlNotificationLog log = invocation.getArgument(0);
            savedLogs.add(log);
            return log;
        }).when(logRepository).save(any(ControlNotificationLog.class));

        scheduler.runDaily();
        assertThat(savedLogs).hasSize(1);
        assertThat(savedLogs.get(0).getControlId()).isEqualTo(200L);
        assertThat(savedLogs.get(0).getNotificationCode()).isEqualTo("REMINDER_1");
        assertThat(savedLogs.get(0).getScheduledDate()).isEqualTo(TODAY);
        scheduler.runDaily();

        verify(controlRepository, times(2)).findAllForReminders();
        verify(notificationService, times(1)).sendTemplateNotifications(
                any(Control.class),
                eq(List.of("facilitator@kpmg.kz", "operator@kpmg.kz")),
                eq(NotificationTemplateService.TemplateType.REMINDER_1_OPEN),
                eq(false)
        );
        verify(logRepository, times(1)).save(any(ControlNotificationLog.class));
        assertThat(savedLogs).hasSize(1);
        verify(logRepository, times(2))
                .existsByControlIdAndNotificationCodeAndScheduledDate(200L, "REMINDER_1", TODAY);
    }

    private ReminderControlProjection projectionFor(Control control, ControlAssignmentDTO assignment) {
        ReminderControlProjection projection = org.mockito.Mockito.mock(ReminderControlProjection.class);
        org.mockito.Mockito.when(projection.getControlId()).thenReturn(control.getId());
        org.mockito.Mockito.when(projection.getControlName()).thenReturn(control.getControlId());
        org.mockito.Mockito.when(projection.getFrequency()).thenReturn(control.getControlFrequency());
        org.mockito.Mockito.when(projection.getStatus()).thenReturn(control.getControlStatus());
        org.mockito.Mockito.when(projection.getOperationDate()).thenReturn(assignment.getControlOperationDate());
        org.mockito.Mockito.when(projection.getDeadlineDate()).thenReturn(assignment.getControlOperationDeadline());
        org.mockito.Mockito.when(projection.getFacilitator()).thenReturn(join(assignment.getFacilitator()));
        org.mockito.Mockito.when(projection.getControlOperator()).thenReturn(join(assignment.getControlOperator()));
        org.mockito.Mockito.when(projection.getSoqmLead()).thenReturn(join(assignment.getSoqmLead()));
        org.mockito.Mockito.when(projection.getProcessOwner()).thenReturn(join(assignment.getProcessOwner()));
        return projection;
    }

    private String join(List<String> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        return String.join(",", items);
    }
}


