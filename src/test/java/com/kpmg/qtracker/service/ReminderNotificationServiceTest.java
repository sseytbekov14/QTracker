package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlNotificationLog;
import com.kpmg.qtracker.repository.ControlNotificationLogRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.ReminderControlProjection;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReminderNotificationServiceTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 2, 6);
    private static final String CODE_OVERDUE_1 = "OVERDUE_1";
    private static final String CODE_OVERDUE_REPEAT = "OVERDUE_REPEAT";

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

    @Spy
    private WorkingDaysService workingDaysService = new WorkingDaysService();

    @InjectMocks
    private ReminderNotificationService service;

    @Test
    void sendsMonthlyReminder1OnDay3() {
        Control control = controlWithFrequency(10L, "Monthly");
        LocalDate operationDate = workingDaysService.addWorkingDays(TODAY, -3);
        LocalDate today = TODAY;

        ControlAssignmentDTO assignment = assignmentWithDates(operationDate, operationDate.plusDays(100));

        service.processControl(control, assignment, today);

        ArgumentCaptor<List<String>> recipients = ArgumentCaptor.forClass(List.class);
        verify(notificationService).sendTemplateNotifications(
                eq(control),
                recipients.capture(),
                eq(NotificationTemplateService.TemplateType.REMINDER_1_OPEN),
                eq(false)
        );
        assertThat(recipients.getValue()).containsExactly("facilitator@kpmg.kz", "operator@kpmg.kz");
        assertLoggedDates(today);
    }

    @Test
    void sendsMonthlyReminder2OnDay6() {
        Control control = controlWithFrequency(11L, "Monthly");
        LocalDate operationDate = workingDaysService.addWorkingDays(TODAY, -6);
        LocalDate today = TODAY;

        ControlAssignmentDTO assignment = assignmentWithDates(operationDate, operationDate.plusDays(100));

        service.processControl(control, assignment, today);

        ArgumentCaptor<List<String>> recipients = ArgumentCaptor.forClass(List.class);
        verify(notificationService).sendTemplateNotifications(
                eq(control),
                recipients.capture(),
                eq(NotificationTemplateService.TemplateType.REMINDER_2_OPEN),
                eq(false)
        );
        assertThat(recipients.getValue()).containsExactly("facilitator@kpmg.kz", "operator@kpmg.kz");
        assertLoggedDates(today);
    }

    @Test
    void reminderSkipsWeekendUsingWorkingDays() {
        Control control = controlWithFrequency(17L, "Monthly");
        LocalDate operationDate = LocalDate.of(2026, 2, 6);
        LocalDate today = workingDaysService.addWorkingDays(operationDate, 3);

        assertThat(today).isEqualTo(LocalDate.of(2026, 2, 11));

        ControlAssignmentDTO assignment = assignmentWithDates(operationDate, operationDate.plusDays(100));

        service.processControl(control, assignment, today);

        ArgumentCaptor<List<String>> recipients = ArgumentCaptor.forClass(List.class);
        verify(notificationService).sendTemplateNotifications(
                eq(control),
                recipients.capture(),
                eq(NotificationTemplateService.TemplateType.REMINDER_1_OPEN),
                eq(false)
        );
        assertThat(recipients.getValue()).containsExactly("facilitator@kpmg.kz", "operator@kpmg.kz");
        assertLoggedDates(today);
    }

    @Test
    void overdue1SkipsWeekendUsingWorkingDays() {
        Control control = controlWithFrequency(18L, "Monthly");
        LocalDate deadlineDate = LocalDate.of(2026, 2, 6);
        LocalDate overdueStart = workingDaysService.addWorkingDays(deadlineDate, 1);
        LocalDate overdue1Date = workingDaysService.addWorkingDays(overdueStart, 1);
        LocalDate operationDate = workingDaysService.addWorkingDays(deadlineDate, -10);

        assertThat(overdueStart).isEqualTo(LocalDate.of(2026, 2, 9));
        assertThat(overdue1Date).isEqualTo(LocalDate.of(2026, 2, 10));

        ControlAssignmentDTO assignment = assignmentWithDates(operationDate, deadlineDate);

        service.processControl(control, assignment, overdue1Date);

        ArgumentCaptor<List<String>> recipients = ArgumentCaptor.forClass(List.class);
        verify(notificationService).sendTemplateNotifications(
                eq(control),
                recipients.capture(),
                eq(NotificationTemplateService.TemplateType.DEADLINE),
                eq(false)
        );
        assertThat(recipients.getValue())
                .containsExactly("facilitator@kpmg.kz", "operator@kpmg.kz", "soqm@kpmg.kz");
        assertLoggedEntry(overdue1Date, CODE_OVERDUE_1);
    }

    @ParameterizedTest
    @MethodSource("reminder1Cases")
    void sendsReminder1OnExpectedDay(Long controlId, String frequency, int dayOffset) {
        Control control = controlWithFrequency(controlId, frequency);
        LocalDate operationDate = workingDaysService.addWorkingDays(TODAY, -dayOffset);
        LocalDate today = TODAY;

        ControlAssignmentDTO assignment = assignmentWithDates(operationDate, operationDate.plusDays(100));

        service.processControl(control, assignment, today);

        ArgumentCaptor<List<String>> recipients = ArgumentCaptor.forClass(List.class);
        verify(notificationService).sendTemplateNotifications(
                eq(control),
                recipients.capture(),
                eq(NotificationTemplateService.TemplateType.REMINDER_1_OPEN),
                eq(false)
        );
        assertThat(recipients.getValue()).containsExactly("facilitator@kpmg.kz", "operator@kpmg.kz");
        assertLoggedDates(today);
    }

    static Stream<Arguments> reminder1Cases() {
        return Stream.of(
                Arguments.of(12L, "Quarterly", 5),
                Arguments.of(13L, "Recurring", 5),
                Arguments.of(14L, "Ad-hoc", 5),
                Arguments.of(15L, "Annual", 5),
                Arguments.of(16L, "Semi Annual", 5)
        );
    }

    @ParameterizedTest
    @MethodSource("reminder2Cases")
    void sendsReminder2OnExpectedDay(Long controlId, String frequency, int dayOffset) {
        Control control = controlWithFrequency(controlId, frequency);
        LocalDate operationDate = workingDaysService.addWorkingDays(TODAY, -dayOffset);
        LocalDate today = TODAY;

        ControlAssignmentDTO assignment = assignmentWithDates(operationDate, operationDate.plusDays(100));

        service.processControl(control, assignment, today);

        ArgumentCaptor<List<String>> recipients = ArgumentCaptor.forClass(List.class);
        verify(notificationService).sendTemplateNotifications(
                eq(control),
                recipients.capture(),
                eq(NotificationTemplateService.TemplateType.REMINDER_2_OPEN),
                eq(false)
        );
        assertThat(recipients.getValue()).containsExactly("facilitator@kpmg.kz", "operator@kpmg.kz");
        assertLoggedDates(today);
    }

    static Stream<Arguments> reminder2Cases() {
        return Stream.of(
                Arguments.of(20L, "Quarterly", 12),
                Arguments.of(21L, "Recurring", 12),
                Arguments.of(22L, "Ad-hoc", 12),
                Arguments.of(23L, "Annual", 25),
                Arguments.of(24L, "Semi Annual", 25)
        );
    }

    @ParameterizedTest
    @MethodSource("notDueReminderCases")
    void doesNotSendReminderWhenNotDue(Long controlId, String frequency, int dayOffset) {
        Control control = controlWithFrequency(controlId, frequency);
        LocalDate operationDate = workingDaysService.addWorkingDays(TODAY, -dayOffset);
        LocalDate today = TODAY;

        ControlAssignmentDTO assignment = assignmentWithDates(operationDate, operationDate.plusDays(100));

        service.processControl(control, assignment, today);

        verifyNoInteractions(notificationService, logRepository);
    }

    static Stream<Arguments> notDueReminderCases() {
        return Stream.of(
                Arguments.of(100L, "Monthly", 2),
                Arguments.of(101L, "Monthly", 4),
                Arguments.of(102L, "Monthly", 7),
                Arguments.of(103L, "Quarterly", 4),
                Arguments.of(104L, "Quarterly", 6),
                Arguments.of(105L, "Quarterly", 13),
                Arguments.of(106L, "Recurring", 4),
                Arguments.of(107L, "Recurring", 6),
                Arguments.of(108L, "Recurring", 13),
                Arguments.of(109L, "Ad-hoc", 4),
                Arguments.of(110L, "Ad-hoc", 6),
                Arguments.of(111L, "Ad-hoc", 13),
                Arguments.of(112L, "Annual", 4),
                Arguments.of(113L, "Annual", 24),
                Arguments.of(114L, "Annual", 26),
                Arguments.of(115L, "Semi Annual", 4),
                Arguments.of(116L, "Semi Annual", 24),
                Arguments.of(117L, "Semi Annual", 26)
        );
    }

    @Test
    void sendsOverdue1OnSecondDayAfterOverdueStarts() {
        Control control = controlWithFrequency(50L, "Monthly");
        LocalDate operationDate = workingDaysService.addWorkingDays(TODAY, -20);
        LocalDate deadlineDate = workingDaysService.addWorkingDays(TODAY, -2);
        LocalDate today = TODAY;

        ControlAssignmentDTO assignment = assignmentWithDates(operationDate, deadlineDate);

        service.processControl(control, assignment, today);

        ArgumentCaptor<List<String>> recipients = ArgumentCaptor.forClass(List.class);
        verify(notificationService).sendTemplateNotifications(
                eq(control),
                recipients.capture(),
                eq(NotificationTemplateService.TemplateType.DEADLINE),
                eq(false)
        );
        assertThat(recipients.getValue())
                .containsExactly("facilitator@kpmg.kz", "operator@kpmg.kz", "soqm@kpmg.kz");
        assertLoggedDates(today);
    }

    @ParameterizedTest
    @MethodSource("overdueEdgeCases")
    void overdueEdgeCasesSendExpectedNotification(int daysAfterDeadline, String expectedCode) {
        Control control = controlWithFrequency(53L + daysAfterDeadline, "Monthly");
        LocalDate deadlineDate = TODAY;
        LocalDate today = workingDaysService.addWorkingDays(deadlineDate, daysAfterDeadline);
        LocalDate operationDate = workingDaysService.addWorkingDays(deadlineDate, -10);

        ControlAssignmentDTO assignment = assignmentWithDates(operationDate, deadlineDate);

        service.processControl(control, assignment, today);

        if (expectedCode == null) {
            verifyNoInteractions(notificationService, logRepository);
            return;
        }

        ArgumentCaptor<List<String>> recipients = ArgumentCaptor.forClass(List.class);
        verify(notificationService).sendTemplateNotifications(
                eq(control),
                recipients.capture(),
                eq(NotificationTemplateService.TemplateType.DEADLINE),
                eq(false)
        );
        assertThat(recipients.getValue())
                .containsExactly("facilitator@kpmg.kz", "operator@kpmg.kz", "soqm@kpmg.kz");
        assertLoggedEntry(today, expectedCode);
    }

    static Stream<Arguments> overdueEdgeCases() {
        return Stream.of(
                Arguments.of(0, null),
                Arguments.of(1, null),
                Arguments.of(2, CODE_OVERDUE_1),
                Arguments.of(3, null),
                Arguments.of(4, CODE_OVERDUE_REPEAT)
        );
    }

    @Test
    void overdueRepeatSendsOnEvenDayWithinWorkingDayWindow() {
        Control control = controlWithFrequency(51L, "Monthly");
        LocalDate overdue1Date = workingDaysService.addWorkingDays(TODAY, -2);
        LocalDate deadlineDate = workingDaysService.addWorkingDays(overdue1Date, -2);
        LocalDate operationDate = workingDaysService.addWorkingDays(TODAY, -30);
        LocalDate today = TODAY;

        ControlAssignmentDTO assignment = assignmentWithDates(operationDate, deadlineDate);

        service.processControl(control, assignment, today);

        ArgumentCaptor<List<String>> recipients = ArgumentCaptor.forClass(List.class);
        verify(notificationService).sendTemplateNotifications(
                eq(control),
                recipients.capture(),
                eq(NotificationTemplateService.TemplateType.DEADLINE),
                eq(false)
        );
        assertThat(recipients.getValue())
                .containsExactly("facilitator@kpmg.kz", "operator@kpmg.kz", "soqm@kpmg.kz");
        assertLoggedDates(today);
    }

    @Test
    void overdueRepeatWindowCrossesWeekend() {
        Control control = controlWithFrequency(54L, "Monthly");
        LocalDate overdue1Date = LocalDate.of(2026, 2, 13);
        LocalDate deadlineDate = workingDaysService.addWorkingDays(overdue1Date, -2);
        LocalDate operationDate = workingDaysService.addWorkingDays(overdue1Date, -20);

        ControlAssignmentDTO assignment = assignmentWithDates(operationDate, deadlineDate);

        LocalDate windowEnd = workingDaysService.addWorkingDays(overdue1Date, 6);
        LocalDate expectedWindowEnd = LocalDate.of(2026, 2, 23);
        assertThat(windowEnd).isEqualTo(expectedWindowEnd);

        LocalDate firstRepeat = LocalDate.of(2026, 2, 17);
        LocalDate secondRepeat = expectedWindowEnd;
        LocalDate afterWindow = expectedWindowEnd.plusDays(1);

        service.processControl(control, assignment, firstRepeat);
        service.processControl(control, assignment, secondRepeat);
        service.processControl(control, assignment, afterWindow);

        ArgumentCaptor<List<String>> recipients = ArgumentCaptor.forClass(List.class);
        verify(notificationService, times(2)).sendTemplateNotifications(
                eq(control),
                recipients.capture(),
                eq(NotificationTemplateService.TemplateType.DEADLINE),
                eq(false)
        );
        assertThat(recipients.getAllValues()).allSatisfy(value -> assertThat(value)
                .containsExactly("facilitator@kpmg.kz", "operator@kpmg.kz", "soqm@kpmg.kz"));
        assertLoggedEntries(CODE_OVERDUE_REPEAT, firstRepeat, secondRepeat);
    }

    @Test
    void overdueRepeatSkipsOutsideWorkingDayWindow() {
        Control control = controlWithFrequency(52L, "Monthly");
        LocalDate overdue1Date = workingDaysService.addWorkingDays(TODAY, -10);
        LocalDate deadlineDate = workingDaysService.addWorkingDays(overdue1Date, -2);
        LocalDate operationDate = workingDaysService.addWorkingDays(TODAY, -30);
        LocalDate today = TODAY;

        ControlAssignmentDTO assignment = assignmentWithDates(operationDate, deadlineDate);

        service.processControl(control, assignment, today);

        verify(notificationService, never()).sendTemplateNotifications(any(), any(), any(), anyBoolean());
        verify(logRepository, never()).save(any());
    }

    @Test
    void idempotencySkipsDuplicateReminderForSameDay() {
        Control control = controlWithFrequency(30L, "Monthly");
        LocalDate operationDate = workingDaysService.addWorkingDays(TODAY, -3);
        LocalDate today = TODAY;

        ControlAssignmentDTO assignment = assignmentWithDates(operationDate, operationDate.plusDays(100));

        when(logRepository.existsByControlIdAndNotificationCodeAndScheduledDate(30L, "REMINDER_1", today))
                .thenReturn(false, true);

        service.processControl(control, assignment, today);
        service.processControl(control, assignment, today);

        verify(notificationService).sendTemplateNotifications(
                eq(control),
                eq(List.of("facilitator@kpmg.kz", "operator@kpmg.kz")),
                eq(NotificationTemplateService.TemplateType.REMINDER_1_OPEN),
                eq(false)
        );
        assertLoggedDates(today);
    }

    @Test
    void runDailyRemindersIsIdempotentForSameDay() {
        Control control = controlWithFrequency(40L, "Monthly");
        LocalDate operationDate = workingDaysService.addWorkingDays(TODAY, -3);
        LocalDate today = TODAY;

        ControlAssignmentDTO assignment = assignmentWithDates(operationDate, operationDate.plusDays(100));

        ReminderControlProjection row = projectionFor(control, assignment);
        when(controlRepository.findAllForReminders()).thenReturn(List.of(row));
        when(logRepository.existsByControlIdAndNotificationCodeAndScheduledDate(40L, "REMINDER_1", today))
                .thenReturn(false, true);

        service.runDailyReminders(today);
        service.runDailyReminders(today);

        verify(notificationService, times(1)).sendTemplateNotifications(
                eq(control),
                eq(List.of("facilitator@kpmg.kz", "operator@kpmg.kz")),
                eq(NotificationTemplateService.TemplateType.REMINDER_1_OPEN),
                eq(false)
        );
        assertLoggedDates(today);
    }

    @Test
    void runDailyRemindersDoesNotCreateFutureDatedNotifications() {
        Control dueToday = controlWithFrequency(90L, "Monthly");
        Control notDue = controlWithFrequency(91L, "Monthly");
        Control overdueNotDue = controlWithFrequency(92L, "Monthly");
        LocalDate today = TODAY;

        ControlAssignmentDTO dueAssignment = assignmentWithDates(workingDaysService.addWorkingDays(today, -3), today.plusDays(100));
        ControlAssignmentDTO notDueAssignment = assignmentWithDates(workingDaysService.addWorkingDays(today, -2), today.plusDays(100));
        LocalDate overdueDeadline = workingDaysService.addWorkingDays(today, -1);
        ControlAssignmentDTO overdueAssignment = assignmentWithDates(workingDaysService.addWorkingDays(today, -30), overdueDeadline);

        ReminderControlProjection dueRow = projectionFor(dueToday, dueAssignment);
        ReminderControlProjection notDueRow = projectionFor(notDue, notDueAssignment);
        ReminderControlProjection overdueRow = projectionFor(overdueNotDue, overdueAssignment);
        when(controlRepository.findAllForReminders()).thenReturn(List.of(dueRow, notDueRow, overdueRow));
        when(logRepository.existsByControlIdAndNotificationCodeAndScheduledDate(anyLong(), anyString(), eq(today)))
                .thenReturn(false, true);

        service.runDailyReminders(today);

        ArgumentCaptor<ControlNotificationLog> logCaptor = ArgumentCaptor.forClass(ControlNotificationLog.class);
        verify(logRepository, times(1)).save(logCaptor.capture());
        List<LocalDate> scheduledDates = logCaptor.getAllValues().stream()
                .map(ControlNotificationLog::getScheduledDate)
                .toList();
        assertThat(scheduledDates).containsExactly(today);
        verify(notificationService, times(1)).sendTemplateNotifications(
                eq(dueToday),
                eq(List.of("facilitator@kpmg.kz", "operator@kpmg.kz")),
                eq(NotificationTemplateService.TemplateType.REMINDER_1_OPEN),
                eq(false)
        );

        service.runDailyReminders(today);

        verify(notificationService, times(1)).sendTemplateNotifications(
                eq(dueToday),
                eq(List.of("facilitator@kpmg.kz", "operator@kpmg.kz")),
                eq(NotificationTemplateService.TemplateType.REMINDER_1_OPEN),
                eq(false)
        );
        verify(logRepository, times(1)).save(any(ControlNotificationLog.class));
    }

    @Test
    void noNotificationsWhenNotDueToday() {
        Control control = controlWithFrequency(60L, "Monthly");
        LocalDate operationDate = workingDaysService.addWorkingDays(TODAY, -1);
        LocalDate today = TODAY;

        ControlAssignmentDTO assignment = assignmentWithDates(operationDate, operationDate.plusDays(100));

        service.processControl(control, assignment, today);

        verify(notificationService, never()).sendTemplateNotifications(any(), any(), any(), anyBoolean());
        verify(logRepository, never()).save(any());
    }

    private Control controlWithFrequency(Long id, String frequency) {
        Control control = new Control();
        control.setId(id);
        control.setControlFrequency(frequency);
        control.setControlStatus("DRAFT");
        return control;
    }

    private ControlAssignmentDTO assignmentWithDates(LocalDate operationDate, LocalDate deadlineDate) {
        ControlAssignmentDTO assignment = new ControlAssignmentDTO();
        assignment.setControlOperationDate(operationDate);
        assignment.setControlOperationDeadline(deadlineDate);
        assignment.setFacilitator(List.of("facilitator@kpmg.kz"));
        assignment.setControlOperator(List.of("operator@kpmg.kz"));
        assignment.setSoqmLead(List.of("soqm@kpmg.kz"));
        return assignment;
    }

    private ReminderControlProjection projectionFor(Control control, ControlAssignmentDTO assignment) {
        if (assignment != null && assignment.getControlOperationDeadline() != null) {
            control.setDeadline(assignment.getControlOperationDeadline());
        }
        ReminderControlProjection projection = org.mockito.Mockito.mock(ReminderControlProjection.class);
        when(projection.getControlId()).thenReturn(control.getId());
        when(projection.getControlName()).thenReturn(control.getControlId());
        when(projection.getFrequency()).thenReturn(control.getControlFrequency());
        when(projection.getStatus()).thenReturn(control.getControlStatus());
        when(projection.getOperationDate()).thenReturn(assignment.getControlOperationDate());
        when(projection.getDeadlineDate()).thenReturn(assignment.getControlOperationDeadline());
        when(projection.getFacilitator()).thenReturn(join(assignment.getFacilitator()));
        when(projection.getControlOperator()).thenReturn(join(assignment.getControlOperator()));
        when(projection.getSoqmLead()).thenReturn(join(assignment.getSoqmLead()));
        when(projection.getProcessOwner()).thenReturn(join(assignment.getProcessOwner()));
        return projection;
    }

    private String join(List<String> items) {
        if (items == null || items.isEmpty()) {
            return null;
        }
        return String.join(",", items);
    }

    private void assertLoggedDates(LocalDate... expectedDates) {
        ArgumentCaptor<ControlNotificationLog> logCaptor = ArgumentCaptor.forClass(ControlNotificationLog.class);
        verify(logRepository, times(expectedDates.length)).save(logCaptor.capture());
        List<LocalDate> actualDates = logCaptor.getAllValues().stream()
            .map(ControlNotificationLog::getScheduledDate)
            .toList();
        assertThat(actualDates).containsExactly(expectedDates);
    }

    private void assertLoggedEntry(LocalDate expectedDate, String expectedCode) {
        ArgumentCaptor<ControlNotificationLog> logCaptor = ArgumentCaptor.forClass(ControlNotificationLog.class);
        verify(logRepository).save(logCaptor.capture());
        ControlNotificationLog log = logCaptor.getValue();
        assertThat(log.getScheduledDate()).isEqualTo(expectedDate);
        assertThat(log.getNotificationCode()).isEqualTo(expectedCode);
    }

    private void assertLoggedEntries(String expectedCode, LocalDate... expectedDates) {
        ArgumentCaptor<ControlNotificationLog> logCaptor = ArgumentCaptor.forClass(ControlNotificationLog.class);
        verify(logRepository, times(expectedDates.length)).save(logCaptor.capture());
        List<ControlNotificationLog> logs = logCaptor.getAllValues();
        List<LocalDate> actualDates = logs.stream()
                .map(ControlNotificationLog::getScheduledDate)
                .toList();
        assertThat(actualDates).containsExactly(expectedDates);
        assertThat(logs).allSatisfy(log -> assertThat(log.getNotificationCode()).isEqualTo(expectedCode));
    }
}

