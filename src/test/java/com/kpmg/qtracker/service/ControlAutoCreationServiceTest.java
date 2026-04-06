package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ControlAutoCreationServiceTest {

    @Mock
    private ControlRepository controlRepository;

    @Mock
    private ControlAssignmentRepository assignmentRepository;

    @Mock
    private ControlAssignmentService controlAssignmentService;

    @Mock
    private NotificationService notificationService;

    @Mock
    private ControlIdGeneratorService controlIdGeneratorService;

    @Spy
    private ControlScheduleCalculator scheduleCalculator = new ControlScheduleCalculator();

    @InjectMocks
    private ControlAutoCreationService autoCreationService;

    @BeforeEach
    void configureControlIdGenerator() {
        lenient().when(controlIdGeneratorService.extractBaseId(anyString()))
                .thenAnswer(invocation -> {
                    String controlId = invocation.getArgument(0);
                    if (controlId == null) {
                        return null;
                    }
                    int slash = controlId.indexOf('/');
                    return slash > 0 ? controlId.substring(0, slash) : controlId;
                });

        lenient().when(controlIdGeneratorService.generateNextPeriodControlId(anyString(), anyString(), any(LocalDate.class)))
                .thenAnswer(invocation -> {
                    String baseId = invocation.getArgument(0);
                    LocalDate operationDate = invocation.getArgument(2);
                    String candidate = baseId + "_" + operationDate.format(DateTimeFormatter.ofPattern("MMM-yyyy", Locale.ENGLISH));
                    if (!controlRepository.existsByControlId(candidate)) {
                        return candidate;
                    }
                    for (int i = 1; i <= 100; i++) {
                        String withSuffix = candidate + " (" + i + ")";
                        if (!controlRepository.existsByControlId(withSuffix)) {
                            return withSuffix;
                        }
                    }
                    return candidate + " (overflow)";
                });
    }

    @Test
    void shouldCreateNextOccurrence_whenDueToday_monthly() {
        LocalDate operationDate = LocalDate.of(2026, 2, 4);
        LocalDate nextOperationDate = operationDate.plusMonths(1);
        LocalDate today = nextOperationDate;

        Control previous = controlWithFrequency("Monthly");
        ControlAssignment previousAssignment = assignmentWithDates(operationDate, nextOperationDate);
        previous.setSoqmHeadComments("comment");
        previous.setProcessOwnerComments("comment");
        previous.setAttachmentDetailsPath("details.pdf");
        previous.setAttachmentDocumentsPath("docs.pdf");

        when(assignmentRepository.existsByBaseControlIdAndOperationDate(anyString(), anyString(), any(LocalDate.class)))
                .thenReturn(false);
        when(controlRepository.existsByControlId(anyString())).thenReturn(false);
        when(controlRepository.save(any(Control.class))).thenAnswer(invocation -> {
            Control saved = invocation.getArgument(0);
            saved.setId(99L);
            return saved;
        });
        when(assignmentRepository.save(any(ControlAssignment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(controlAssignmentService.getAssignmentByControlId(any(Long.class)))
                .thenReturn(assignmentDtoWithSoqm("soqm@example.test"));

        ControlAutoCreationService.AutoCreationResult result =
                autoCreationService.createNextOccurrenceIfDue(previous, previousAssignment, today);

        assertNotNull(result);
        assertTrue(result.created());
        assertNotNull(result.newControl());
        assertNotNull(result.newAssignment());
        assertEquals(today, result.newAssignment().getControlOperationDate());
        assertEquals(today.plusMonths(1), result.newAssignment().getNextControlOperationDate());
        assertEquals(expectedDeadline(today, "Monthly"), result.newAssignment().getControlOperationDeadline());
        assertEquals("DRAFT", result.newControl().getControlStatus());
        assertEquals(previousAssignment.getFacilitator(), result.newAssignment().getFacilitator());
        assertEquals(previousAssignment.getControlOperator(), result.newAssignment().getControlOperator());
        assertEquals(previousAssignment.getSoqmLead(), result.newAssignment().getSoqmLead());
        assertEquals(previousAssignment.getProcessOwner(), result.newAssignment().getProcessOwner());
        assertNull(result.newControl().getSoqmHeadComments());
        assertNull(result.newControl().getProcessOwnerComments());
        assertNull(result.newControl().getAttachmentDetailsPath());
        assertNull(result.newControl().getAttachmentDocumentsPath());
        assertEquals("CTRL-001_Mar-2026", result.newControl().getControlId());
        verify(notificationService, times(1))
                .sendAutoCreatedNotification(any(Control.class), anyString(), anyString(), any(LocalDate.class));
        verify(controlAssignmentService, times(1)).recalculateSchedule(99L);
    }

    @Test
    void shouldNotCreate_whenNotDueYet() {
        LocalDate operationDate = LocalDate.of(2026, 2, 4);
        LocalDate nextOperationDate = operationDate.plusMonths(1);
        LocalDate today = operationDate.plusDays(10);

        Control previous = controlWithFrequency("Monthly");
        ControlAssignment previousAssignment = assignmentWithDates(operationDate, nextOperationDate);

        ControlAutoCreationService.AutoCreationResult result =
                autoCreationService.createNextOccurrenceIfDue(previous, previousAssignment, today);

        assertNotNull(result);
        assertFalse(result.created());
        verify(controlRepository, never()).save(any(Control.class));
        verify(assignmentRepository, never()).save(any(ControlAssignment.class));
    }

    @Test
    void shouldNotCreateDuplicate_whenAlreadyExists() {
        LocalDate operationDate = LocalDate.of(2026, 2, 4);
        LocalDate nextOperationDate = operationDate.plusMonths(1);
        LocalDate today = nextOperationDate;

        Control previous = controlWithFrequency("Monthly");
        ControlAssignment previousAssignment = assignmentWithDates(operationDate, nextOperationDate);

        when(assignmentRepository.existsByBaseControlIdAndOperationDate(anyString(), anyString(), any(LocalDate.class)))
                .thenReturn(true);

        ControlAutoCreationService.AutoCreationResult result =
                autoCreationService.createNextOccurrenceIfDue(previous, previousAssignment, today);

        assertNotNull(result);
        assertFalse(result.created());
        verify(controlRepository, never()).save(any(Control.class));
        verify(assignmentRepository, never()).save(any(ControlAssignment.class));
        verify(notificationService, never()).sendAutoCreatedNotification(any(), any(), any(), any());
    }

    @Test
    void shouldNotCreate_forNonMonthlyFrequency() {
        LocalDate operationDate = LocalDate.of(2026, 2, 4);
        LocalDate nextOperationDate = operationDate.plusMonths(3);
        LocalDate today = nextOperationDate;

        Control previous = controlWithFrequency("Ad-hoc");
        ControlAssignment previousAssignment = assignmentWithDates(operationDate, nextOperationDate);

        ControlAutoCreationService.AutoCreationResult result =
                autoCreationService.createNextOccurrenceIfDue(previous, previousAssignment, today);

        assertNotNull(result);
        assertFalse(result.created());
        verify(controlRepository, never()).save(any(Control.class));
        verify(assignmentRepository, never()).save(any(ControlAssignment.class));
    }

    @Test
    void shouldCreateNextOccurrence_whenDueToday_quarterly() {
        LocalDate operationDate = LocalDate.of(2026, 1, 15);
        LocalDate nextOperationDate = operationDate.plusMonths(3);
        LocalDate today = nextOperationDate;

        Control previous = controlWithFrequency("Quarterly");
        ControlAssignment previousAssignment = assignmentWithDates(operationDate, nextOperationDate);

        when(assignmentRepository.existsByBaseControlIdAndOperationDate(anyString(), anyString(), any(LocalDate.class)))
                .thenReturn(false);
        when(controlRepository.existsByControlId(anyString())).thenReturn(false);
        when(controlRepository.save(any(Control.class))).thenAnswer(invocation -> {
            Control saved = invocation.getArgument(0);
            saved.setId(120L);
            return saved;
        });
        when(assignmentRepository.save(any(ControlAssignment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(controlAssignmentService.getAssignmentByControlId(any(Long.class)))
                .thenReturn(assignmentDtoWithSoqm("soqm@example.test"));

        ControlAutoCreationService.AutoCreationResult result =
                autoCreationService.createNextOccurrenceIfDue(previous, previousAssignment, today);

        assertNotNull(result);
        assertTrue(result.created());
        assertEquals(today, result.newAssignment().getControlOperationDate());
        assertEquals(today.plusMonths(3), result.newAssignment().getNextControlOperationDate());
        assertEquals(expectedDeadline(today, "Quarterly"), result.newAssignment().getControlOperationDeadline());
        assertEquals("DRAFT", result.newControl().getControlStatus());
        assertEquals("CTRL-001_Apr-2026", result.newControl().getControlId());
        verify(notificationService, times(1))
                .sendAutoCreatedNotification(any(Control.class), anyString(), anyString(), any(LocalDate.class));
        verify(controlAssignmentService, times(1)).recalculateSchedule(120L);
    }

    @Test
    void runDailyAutoCreationIsIdempotentForSameDay() {
        LocalDate operationDate = LocalDate.of(2026, 2, 4);
        LocalDate nextOperationDate = operationDate.plusMonths(1);
        LocalDate today = nextOperationDate;

        Control previous = controlWithFrequency("Monthly");
        ControlAssignment previousAssignment = assignmentWithDates(operationDate, nextOperationDate);

        when(assignmentRepository.findMonthlyByNextControlOperationDateRange(today.atStartOfDay(),
                today.plusDays(1).atStartOfDay()))
                .thenReturn(List.of(previousAssignment));
        when(assignmentRepository.findQuarterlyByNextControlOperationDateRange(today.atStartOfDay(),
                today.plusDays(1).atStartOfDay()))
                .thenReturn(List.of());
        when(assignmentRepository.findRecurringByNextControlOperationDateRange(today.atStartOfDay(),
                today.plusDays(1).atStartOfDay()))
                .thenReturn(List.of());
        when(assignmentRepository.findAnnualByNextControlOperationDateRange(today.atStartOfDay(),
                today.plusDays(1).atStartOfDay()))
                .thenReturn(List.of());
        when(controlRepository.findById(previous.getId())).thenReturn(Optional.of(previous));
        when(assignmentRepository.existsByBaseControlIdAndOperationDate(anyString(), anyString(), any(LocalDate.class)))
                .thenReturn(false, true);
        when(controlRepository.existsByControlId(anyString())).thenReturn(false);
        when(controlRepository.save(any(Control.class))).thenAnswer(invocation -> {
            Control saved = invocation.getArgument(0);
            saved.setId(101L);
            return saved;
        });
        when(assignmentRepository.save(any(ControlAssignment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(controlAssignmentService.getAssignmentByControlId(any(Long.class)))
                .thenReturn(assignmentDtoWithSoqm("soqm@example.test"));

        autoCreationService.runDailyAutoCreation(today);
        autoCreationService.runDailyAutoCreation(today);

        verify(controlRepository, times(1)).save(any(Control.class));
        verify(assignmentRepository, times(1)).save(any(ControlAssignment.class));
        verify(notificationService, times(1))
                .sendAutoCreatedNotification(any(Control.class), anyString(), anyString(), any(LocalDate.class));
        verify(controlAssignmentService, times(1)).recalculateSchedule(101L);
    }

    @Test
    void runDailyAutoCreation_createsForMidnightNextDate() {
        LocalDate operationDate = LocalDate.of(2026, 2, 4);
        LocalDate nextOperationDate = operationDate.plusMonths(1);
        LocalDateTime dayStart = nextOperationDate.atStartOfDay();
        LocalDateTime nextDayStart = nextOperationDate.plusDays(1).atStartOfDay();

        Control previous = controlWithFrequency("Monthly");
        ControlAssignment previousAssignment = assignmentWithDates(operationDate, nextOperationDate);

        when(assignmentRepository.findMonthlyByNextControlOperationDateRange(dayStart, nextDayStart))
                .thenReturn(List.of(previousAssignment));
        when(assignmentRepository.findQuarterlyByNextControlOperationDateRange(dayStart, nextDayStart))
                .thenReturn(List.of());
        when(assignmentRepository.findRecurringByNextControlOperationDateRange(dayStart, nextDayStart))
                .thenReturn(List.of());
        when(assignmentRepository.findAnnualByNextControlOperationDateRange(dayStart, nextDayStart))
                .thenReturn(List.of());
        when(controlRepository.findById(previous.getId())).thenReturn(Optional.of(previous));
        when(assignmentRepository.existsByBaseControlIdAndOperationDate(anyString(), anyString(), any(LocalDate.class)))
                .thenReturn(false);
        when(controlRepository.existsByControlId(anyString())).thenReturn(false);
        when(controlRepository.save(any(Control.class))).thenAnswer(invocation -> {
            Control saved = invocation.getArgument(0);
            saved.setId(102L);
            return saved;
        });
        when(assignmentRepository.save(any(ControlAssignment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(controlAssignmentService.getAssignmentByControlId(any(Long.class)))
                .thenReturn(assignmentDtoWithSoqm("soqm@example.test"));

        ControlAutoCreationService.AutoCreationRunSummary summary =
                autoCreationService.runDailyAutoCreation(dayStart, nextDayStart);

        assertNotNull(summary);
        assertEquals(1, summary.candidates());
        assertEquals(1, summary.created());
        assertEquals(0, summary.duplicatesSkipped());
        verify(controlRepository, times(1)).save(any(Control.class));
        verify(assignmentRepository, times(1)).save(any(ControlAssignment.class));
        verify(notificationService, times(1))
                .sendAutoCreatedNotification(any(Control.class), anyString(), anyString(), any(LocalDate.class));
        verify(controlAssignmentService, times(1)).recalculateSchedule(102L);
    }

    @Test
    void runDailyAutoCreation_quarterly_midnightTimestamp_createsOnce() {
        LocalDate operationDate = LocalDate.of(2026, 1, 15);
        LocalDate nextOperationDate = operationDate.plusMonths(3);
        LocalDateTime dayStart = nextOperationDate.atStartOfDay();
        LocalDateTime nextDayStart = nextOperationDate.plusDays(1).atStartOfDay();

        Control previous = controlWithFrequency("Quarterly");
        ControlAssignment previousAssignment = assignmentWithDates(operationDate, nextOperationDate);

        when(assignmentRepository.findMonthlyByNextControlOperationDateRange(dayStart, nextDayStart))
                .thenReturn(List.of());
        when(assignmentRepository.findQuarterlyByNextControlOperationDateRange(dayStart, nextDayStart))
                .thenReturn(List.of(previousAssignment));
        when(assignmentRepository.findRecurringByNextControlOperationDateRange(dayStart, nextDayStart))
                .thenReturn(List.of());
        when(assignmentRepository.findAnnualByNextControlOperationDateRange(dayStart, nextDayStart))
                .thenReturn(List.of());
        when(controlRepository.findById(previous.getId())).thenReturn(Optional.of(previous));
        when(controlRepository.existsByControlId(anyString())).thenReturn(false);
        when(controlRepository.save(any(Control.class))).thenAnswer(invocation -> {
            Control saved = invocation.getArgument(0);
            saved.setId(130L);
            return saved;
        });
        when(assignmentRepository.save(any(ControlAssignment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(controlAssignmentService.getAssignmentByControlId(any(Long.class)))
                .thenReturn(assignmentDtoWithSoqm("soqm@example.test"));

        ControlAutoCreationService.AutoCreationRunSummary summary =
                autoCreationService.runDailyAutoCreation(dayStart, nextDayStart);

        assertNotNull(summary);
        assertEquals(1, summary.candidates());
        assertEquals(1, summary.created());
        assertEquals(0, summary.duplicatesSkipped());
        verify(controlRepository, times(1)).save(any(Control.class));
        verify(assignmentRepository, times(1)).save(any(ControlAssignment.class));
        verify(notificationService, times(1))
                .sendAutoCreatedNotification(any(Control.class), anyString(), anyString(), any(LocalDate.class));
        verify(controlAssignmentService, times(1)).recalculateSchedule(130L);
    }

    @Test
    void runDailyAutoCreation_createsFromAutoCreatedOccurrence() {
        LocalDate operationDate = LocalDate.of(2026, 2, 1);
        LocalDate nextOperationDate = operationDate.plusMonths(1);
        LocalDateTime dayStart = nextOperationDate.atStartOfDay();
        LocalDateTime nextDayStart = nextOperationDate.plusDays(1).atStartOfDay();

        Control autoCreated = controlWithFrequency("Monthly");
        autoCreated.setControlId("a2_2026-02");
        ControlAssignment autoAssignment = assignmentWithDates(operationDate, nextOperationDate);

        when(assignmentRepository.findMonthlyByNextControlOperationDateRange(dayStart, nextDayStart))
                .thenReturn(List.of(autoAssignment));
        when(assignmentRepository.findQuarterlyByNextControlOperationDateRange(dayStart, nextDayStart))
                .thenReturn(List.of());
        when(assignmentRepository.findRecurringByNextControlOperationDateRange(dayStart, nextDayStart))
                .thenReturn(List.of());
        when(controlRepository.findById(autoCreated.getId())).thenReturn(Optional.of(autoCreated));
        when(controlRepository.existsByControlId(anyString())).thenReturn(false);
        when(controlRepository.save(any(Control.class))).thenAnswer(invocation -> {
            Control saved = invocation.getArgument(0);
            saved.setId(140L);
            return saved;
        });
        when(assignmentRepository.save(any(ControlAssignment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(controlAssignmentService.getAssignmentByControlId(any(Long.class)))
                .thenReturn(assignmentDtoWithSoqm("soqm@example.test"));

        ControlAutoCreationService.AutoCreationRunSummary summary =
                autoCreationService.runDailyAutoCreation(dayStart, nextDayStart);

        assertNotNull(summary);
        assertEquals(1, summary.candidates());
        assertEquals(1, summary.created());
        verify(controlRepository, times(1)).save(any(Control.class));
        verify(controlAssignmentService, times(1)).recalculateSchedule(140L);
    }

    @Test
    void generatesUniqueControlId_withCollisionSuffix() {
        LocalDate operationDate = LocalDate.of(2026, 2, 13);
        LocalDate nextOperationDate = operationDate.plusMonths(1);
        LocalDate today = nextOperationDate;

        Control previous = controlWithFrequency("Monthly");
        previous.setControlId("a2");
        ControlAssignment previousAssignment = assignmentWithDates(operationDate, nextOperationDate);

        when(assignmentRepository.existsByBaseControlIdAndOperationDate(anyString(), anyString(), any(LocalDate.class)))
                .thenReturn(false);
        when(controlRepository.existsByControlId(anyString())).thenReturn(true, false);
        when(controlRepository.save(any(Control.class))).thenAnswer(invocation -> {
            Control saved = invocation.getArgument(0);
            saved.setId(150L);
            return saved;
        });
        when(assignmentRepository.save(any(ControlAssignment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(controlAssignmentService.getAssignmentByControlId(any(Long.class)))
                .thenReturn(assignmentDtoWithSoqm("soqm@example.test"));

        ControlAutoCreationService.AutoCreationResult result =
                autoCreationService.createNextOccurrenceIfDue(previous, previousAssignment, today);

        assertTrue(result.created());
        assertEquals("a2_Mar-2026 (1)", result.newControl().getControlId());
    }

    @Test
    void shouldCreateNextOccurrence_whenDueToday_recurring() {
        LocalDate operationDate = LocalDate.of(2026, 1, 20);
        LocalDate nextOperationDate = operationDate.plusMonths(3);
        LocalDate today = nextOperationDate;

        Control previous = controlWithFrequency("Recurring");
        ControlAssignment previousAssignment = assignmentWithDates(operationDate, nextOperationDate);

        when(assignmentRepository.existsByBaseControlIdAndOperationDate(anyString(), anyString(), any(LocalDate.class)))
                .thenReturn(false);
        when(controlRepository.existsByControlId(anyString())).thenReturn(false);
        when(controlRepository.save(any(Control.class))).thenAnswer(invocation -> {
            Control saved = invocation.getArgument(0);
            saved.setId(160L);
            return saved;
        });
        when(assignmentRepository.save(any(ControlAssignment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(controlAssignmentService.getAssignmentByControlId(any(Long.class)))
                .thenReturn(assignmentDtoWithSoqm("soqm@example.test"));

        ControlAutoCreationService.AutoCreationResult result =
                autoCreationService.createNextOccurrenceIfDue(previous, previousAssignment, today);

        assertNotNull(result);
        assertTrue(result.created());
        assertEquals(today, result.newAssignment().getControlOperationDate());
        assertEquals(today.plusMonths(3), result.newAssignment().getNextControlOperationDate());
        assertEquals(expectedDeadline(today, "Recurring"), result.newAssignment().getControlOperationDeadline());
        assertEquals("DRAFT", result.newControl().getControlStatus());
        assertEquals("CTRL-001_Apr-2026", result.newControl().getControlId());
    }

    @Test
    void runDailyAutoCreation_recurring_midnightTimestamp_createsOnce() {
        LocalDate operationDate = LocalDate.of(2026, 1, 20);
        LocalDate nextOperationDate = operationDate.plusMonths(3);
        LocalDateTime dayStart = nextOperationDate.atStartOfDay();
        LocalDateTime nextDayStart = nextOperationDate.plusDays(1).atStartOfDay();

        Control previous = controlWithFrequency("Recurring");
        ControlAssignment previousAssignment = assignmentWithDates(operationDate, nextOperationDate);

        when(assignmentRepository.findMonthlyByNextControlOperationDateRange(dayStart, nextDayStart))
                .thenReturn(List.of());
        when(assignmentRepository.findQuarterlyByNextControlOperationDateRange(dayStart, nextDayStart))
                .thenReturn(List.of());
        when(assignmentRepository.findRecurringByNextControlOperationDateRange(dayStart, nextDayStart))
                .thenReturn(List.of(previousAssignment));
        when(assignmentRepository.findSemiAnnualByNextControlOperationDateRange(dayStart, nextDayStart))
                .thenReturn(List.of());
        when(assignmentRepository.findAnnualByNextControlOperationDateRange(dayStart, nextDayStart))
                .thenReturn(List.of());
        when(controlRepository.findById(previous.getId())).thenReturn(Optional.of(previous));
        when(assignmentRepository.existsByBaseControlIdAndOperationDate(anyString(), anyString(), any(LocalDate.class)))
                .thenReturn(false);
        when(controlRepository.existsByControlId(anyString())).thenReturn(false);
        when(controlRepository.save(any(Control.class))).thenAnswer(invocation -> {
            Control saved = invocation.getArgument(0);
            saved.setId(170L);
            return saved;
        });
        when(assignmentRepository.save(any(ControlAssignment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(controlAssignmentService.getAssignmentByControlId(any(Long.class)))
                .thenReturn(assignmentDtoWithSoqm("soqm@example.test"));

        ControlAutoCreationService.AutoCreationRunSummary summary =
                autoCreationService.runDailyAutoCreation(dayStart, nextDayStart);

        assertNotNull(summary);
        assertEquals(1, summary.candidates());
        assertEquals(1, summary.created());
        assertEquals(0, summary.duplicatesSkipped());
        verify(controlRepository, times(1)).save(any(Control.class));
    }

    @Test
    void shouldCreateNextOccurrence_whenDueToday_annual() {
        LocalDate operationDate = LocalDate.of(2025, 3, 10);
        LocalDate nextOperationDate = operationDate.plusMonths(12);
        LocalDate today = nextOperationDate;

        Control previous = controlWithFrequency("Annual");
        ControlAssignment previousAssignment = assignmentWithDates(operationDate, nextOperationDate);

        when(assignmentRepository.existsByBaseControlIdAndOperationDate(anyString(), anyString(), any(LocalDate.class)))
                .thenReturn(false);
        when(controlRepository.existsByControlId(anyString())).thenReturn(false);
        when(controlRepository.save(any(Control.class))).thenAnswer(invocation -> {
            Control saved = invocation.getArgument(0);
            saved.setId(180L);
            return saved;
        });
        when(assignmentRepository.save(any(ControlAssignment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(controlAssignmentService.getAssignmentByControlId(any(Long.class)))
                .thenReturn(assignmentDtoWithSoqm("soqm@example.test"));

        ControlAutoCreationService.AutoCreationResult result =
                autoCreationService.createNextOccurrenceIfDue(previous, previousAssignment, today);

        assertNotNull(result);
        assertTrue(result.created());
        assertEquals(today, result.newAssignment().getControlOperationDate());
        assertEquals(today.plusMonths(12), result.newAssignment().getNextControlOperationDate());
        assertEquals(expectedDeadline(today, "Annual"), result.newAssignment().getControlOperationDeadline());
        assertEquals("DRAFT", result.newControl().getControlStatus());
        assertEquals("CTRL-001_Mar-2026", result.newControl().getControlId());
    }

    @Test
    void runDailyAutoCreation_annual_midnightTimestamp_createsOnce() {
        LocalDate operationDate = LocalDate.of(2025, 3, 10);
        LocalDate nextOperationDate = operationDate.plusMonths(12);
        LocalDateTime dayStart = nextOperationDate.atStartOfDay();
        LocalDateTime nextDayStart = nextOperationDate.plusDays(1).atStartOfDay();

        Control previous = controlWithFrequency("Annual");
        ControlAssignment previousAssignment = assignmentWithDates(operationDate, nextOperationDate);

        when(assignmentRepository.findMonthlyByNextControlOperationDateRange(dayStart, nextDayStart))
                .thenReturn(List.of());
        when(assignmentRepository.findQuarterlyByNextControlOperationDateRange(dayStart, nextDayStart))
                .thenReturn(List.of());
        when(assignmentRepository.findRecurringByNextControlOperationDateRange(dayStart, nextDayStart))
                .thenReturn(List.of());
        when(assignmentRepository.findAnnualByNextControlOperationDateRange(dayStart, nextDayStart))
                .thenReturn(List.of(previousAssignment));
        when(assignmentRepository.findSemiAnnualByNextControlOperationDateRange(dayStart, nextDayStart))
                .thenReturn(List.of());
        when(controlRepository.findById(previous.getId())).thenReturn(Optional.of(previous));
        when(assignmentRepository.existsByBaseControlIdAndOperationDate(anyString(), anyString(), any(LocalDate.class)))
                .thenReturn(false);
        when(controlRepository.existsByControlId(anyString())).thenReturn(false);
        when(controlRepository.save(any(Control.class))).thenAnswer(invocation -> {
            Control saved = invocation.getArgument(0);
            saved.setId(190L);
            return saved;
        });
        when(assignmentRepository.save(any(ControlAssignment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(controlAssignmentService.getAssignmentByControlId(any(Long.class)))
                .thenReturn(assignmentDtoWithSoqm("soqm@example.test"));

        ControlAutoCreationService.AutoCreationRunSummary summary =
                autoCreationService.runDailyAutoCreation(dayStart, nextDayStart);

        assertNotNull(summary);
        assertEquals(1, summary.candidates());
        assertEquals(1, summary.created());
        assertEquals(0, summary.duplicatesSkipped());
        verify(controlRepository, times(1)).save(any(Control.class));
    }

    @Test
    void shouldCreateNextOccurrence_whenDueToday_semiAnnual() {
        LocalDate operationDate = LocalDate.of(2025, 2, 5);
        LocalDate nextOperationDate = operationDate.plusMonths(6);
        LocalDate today = nextOperationDate;

        Control previous = controlWithFrequency("Semi Annual");
        ControlAssignment previousAssignment = assignmentWithDates(operationDate, nextOperationDate);

        when(assignmentRepository.existsByBaseControlIdAndOperationDate(anyString(), anyString(), any(LocalDate.class)))
                .thenReturn(false);
        when(controlRepository.existsByControlId(anyString())).thenReturn(false);
        when(controlRepository.save(any(Control.class))).thenAnswer(invocation -> {
            Control saved = invocation.getArgument(0);
            saved.setId(200L);
            return saved;
        });
        when(assignmentRepository.save(any(ControlAssignment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(controlAssignmentService.getAssignmentByControlId(any(Long.class)))
                .thenReturn(assignmentDtoWithSoqm("soqm@example.test"));

        ControlAutoCreationService.AutoCreationResult result =
                autoCreationService.createNextOccurrenceIfDue(previous, previousAssignment, today);

        assertNotNull(result);
        assertTrue(result.created());
        assertEquals(today, result.newAssignment().getControlOperationDate());
        assertEquals(today.plusMonths(6), result.newAssignment().getNextControlOperationDate());
        assertEquals(expectedDeadline(today, "Semi Annual"), result.newAssignment().getControlOperationDeadline());
        assertEquals("DRAFT", result.newControl().getControlStatus());
        assertEquals("CTRL-001_Aug-2025", result.newControl().getControlId());
    }

    @Test
    void shouldNotCreateSemiAnnual_whenDuplicateExists() {
        LocalDate operationDate = LocalDate.of(2025, 2, 5);
        LocalDate nextOperationDate = operationDate.plusMonths(6);
        LocalDate today = nextOperationDate;

        Control previous = controlWithFrequency("Semi Annual");
        ControlAssignment previousAssignment = assignmentWithDates(operationDate, nextOperationDate);

        when(assignmentRepository.existsByBaseControlIdAndOperationDate(anyString(), anyString(), any(LocalDate.class)))
                .thenReturn(true);

        ControlAutoCreationService.AutoCreationResult result =
                autoCreationService.createNextOccurrenceIfDue(previous, previousAssignment, today);

        assertNotNull(result);
        assertFalse(result.created());
        verify(controlRepository, never()).save(any(Control.class));
        verify(assignmentRepository, never()).save(any(ControlAssignment.class));
    }

    @Test
    void runDailyAutoCreation_semiAnnual_midnightTimestamp_createsOnce() {
        LocalDate operationDate = LocalDate.of(2025, 2, 5);
        LocalDate nextOperationDate = operationDate.plusMonths(6);
        LocalDateTime dayStart = nextOperationDate.atStartOfDay();
        LocalDateTime nextDayStart = nextOperationDate.plusDays(1).atStartOfDay();

        Control previous = controlWithFrequency("Semi Annual");
        ControlAssignment previousAssignment = assignmentWithDates(operationDate, nextOperationDate);

        when(assignmentRepository.findMonthlyByNextControlOperationDateRange(dayStart, nextDayStart))
                .thenReturn(List.of());
        when(assignmentRepository.findQuarterlyByNextControlOperationDateRange(dayStart, nextDayStart))
                .thenReturn(List.of());
        when(assignmentRepository.findRecurringByNextControlOperationDateRange(dayStart, nextDayStart))
                .thenReturn(List.of());
        when(assignmentRepository.findAnnualByNextControlOperationDateRange(dayStart, nextDayStart))
                .thenReturn(List.of());
        when(assignmentRepository.findSemiAnnualByNextControlOperationDateRange(dayStart, nextDayStart))
                .thenReturn(List.of(previousAssignment));
        when(controlRepository.findById(previous.getId())).thenReturn(Optional.of(previous));
        when(assignmentRepository.existsByBaseControlIdAndOperationDate(anyString(), anyString(), any(LocalDate.class)))
                .thenReturn(false);
        when(controlRepository.existsByControlId(anyString())).thenReturn(false);
        when(controlRepository.save(any(Control.class))).thenAnswer(invocation -> {
            Control saved = invocation.getArgument(0);
            saved.setId(210L);
            return saved;
        });
        when(assignmentRepository.save(any(ControlAssignment.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(controlAssignmentService.getAssignmentByControlId(any(Long.class)))
                .thenReturn(assignmentDtoWithSoqm("soqm@example.test"));

        ControlAutoCreationService.AutoCreationRunSummary summary =
                autoCreationService.runDailyAutoCreation(dayStart, nextDayStart);

        assertNotNull(summary);
        assertEquals(1, summary.candidates());
        assertEquals(1, summary.created());
        assertEquals(0, summary.duplicatesSkipped());
        verify(controlRepository, times(1)).save(any(Control.class));
    }

    private ControlAssignmentDTO assignmentDtoWithSoqm(String email) {
        ControlAssignmentDTO dto = new ControlAssignmentDTO();
        dto.setSoqmLead(List.of(email));
        return dto;
    }

    private Control controlWithFrequency(String frequency) {
        Control control = new Control();
        control.setId(10L);
        control.setControlId("CTRL-001");
        control.setControlFrequency(frequency);
        control.setControlStatus("DRAFT");
        control.setControlDescription("Base control");
        return control;
    }

    private ControlAssignment assignmentWithDates(LocalDate operationDate, LocalDate nextOperationDate) {
        ControlAssignment assignment = new ControlAssignment();
        assignment.setControlId(10L);
        assignment.setFacilitator("facilitator@example.test");
        assignment.setControlOperator("operator@example.test");
        assignment.setSoqmLead("soqm@example.test");
        assignment.setProcessOwner("owner@example.test");
        assignment.setControlOperationDate(operationDate);
        assignment.setNextControlOperationDate(nextOperationDate);
        return assignment;
    }

    private LocalDate expectedDeadline(LocalDate operationDate, String frequency) {
        if (operationDate == null) {
            return null;
        }
        String normalized = frequency == null ? "" : frequency.toLowerCase();
        if (normalized.contains("monthly")) {
            return operationDate.plusDays(7);
        }
        if (normalized.contains("quarterly")) {
            return operationDate.plusDays(14);
        }
        if (normalized.contains("recurring")) {
            return operationDate.plusDays(14);
        }
        if (normalized.contains("ad") && normalized.contains("hoc")) {
            return operationDate.plusDays(14);
        }
        if (normalized.contains("annual") || normalized.contains("semi")) {
            return operationDate.plusMonths(1);
        }
        return null;
    }
}

