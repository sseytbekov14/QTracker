package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@Disabled("TODO: enable when ControlAutoCreationService is implemented")
@ExtendWith(MockitoExtension.class)
class ControlAutoCreationServiceTest {

    @Mock
    private OccurrenceLookup occurrenceLookup;

    private AutoCreationService autoCreationService;

    @BeforeEach
    void setUp() {
        autoCreationService = new PlaceholderAutoCreationService(occurrenceLookup);
    }

    @Test
    void shouldCreateNextOccurrence_whenDueToday_monthly() {
        LocalDate operationDate = LocalDate.of(2026, 2, 4);
        LocalDate nextOperationDate = operationDate.plusMonths(1);
        LocalDate today = nextOperationDate;

        Control previous = controlWithFrequency("Monthly");
        ControlAssignment previousAssignment = assignmentWithDates(operationDate, nextOperationDate);
        previous.setControlOperatorsProgram("results");
        previous.setSoqmHeadComments("comment");
        previous.setProcessOwnerComments("comment");
        previous.setAttachmentDetailsPath("details.pdf");
        previous.setAttachmentDocumentsPath("docs.pdf");

        when(occurrenceLookup.exists(previous, nextOperationDate)).thenReturn(false);

        AutoCreationResult result = autoCreationService.createNextOccurrenceIfDue(previous, previousAssignment, today);

        assertNotNull(result);
        assertTrue(result.created());
        assertNotNull(result.newControl());
        assertNotNull(result.newAssignment());
        assertEquals(nextOperationDate, result.newAssignment().getControlOperationDate());
        assertEquals(nextOperationDate.plusMonths(1), result.newAssignment().getNextControlOperationDate());
        assertEquals(expectedDeadline(nextOperationDate, "Monthly"), result.newAssignment().getControlOperationDeadline());
        assertEquals("In Progress", result.newControl().getControlStatus());
        assertEquals(previousAssignment.getFacilitator(), result.newAssignment().getFacilitator());
        assertEquals(previousAssignment.getControlOperator(), result.newAssignment().getControlOperator());
        assertEquals(previousAssignment.getSoqmLead(), result.newAssignment().getSoqmLead());
        assertEquals(previousAssignment.getProcessOwner(), result.newAssignment().getProcessOwner());
        assertEquals(null, result.newControl().getControlOperatorsProgram());
        assertEquals(null, result.newControl().getSoqmHeadComments());
        assertEquals(null, result.newControl().getProcessOwnerComments());
        assertEquals(null, result.newControl().getAttachmentDetailsPath());
        assertEquals(null, result.newControl().getAttachmentDocumentsPath());
    }

    @Test
    void shouldNotCreate_whenNotDueYet() {
        LocalDate operationDate = LocalDate.of(2026, 2, 4);
        LocalDate nextOperationDate = operationDate.plusMonths(1);
        LocalDate today = operationDate.plusDays(10);

        Control previous = controlWithFrequency("Monthly");
        ControlAssignment previousAssignment = assignmentWithDates(operationDate, nextOperationDate);

        when(occurrenceLookup.exists(any(Control.class), any(LocalDate.class))).thenReturn(false);

        AutoCreationResult result = autoCreationService.createNextOccurrenceIfDue(previous, previousAssignment, today);

        assertNotNull(result);
        assertFalse(result.created());
    }

    @Test
    void shouldNotCreateDuplicate_whenAlreadyExists() {
        LocalDate operationDate = LocalDate.of(2026, 2, 4);
        LocalDate nextOperationDate = operationDate.plusMonths(1);
        LocalDate today = nextOperationDate;

        Control previous = controlWithFrequency("Monthly");
        ControlAssignment previousAssignment = assignmentWithDates(operationDate, nextOperationDate);

        when(occurrenceLookup.exists(previous, nextOperationDate)).thenReturn(true);

        AutoCreationResult result = autoCreationService.createNextOccurrenceIfDue(previous, previousAssignment, today);

        assertNotNull(result);
        assertFalse(result.created());
    }

    @ParameterizedTest
    @MethodSource("frequencyCases")
    void shouldCalculateNextOperationDate_byFrequency(String frequency, int monthsToAdd) {
        LocalDate operationDate = LocalDate.of(2026, 2, 4);
        LocalDate nextOperationDate = operationDate.plusMonths(monthsToAdd);
        LocalDate today = nextOperationDate;

        Control previous = controlWithFrequency(frequency);
        ControlAssignment previousAssignment = assignmentWithDates(operationDate, nextOperationDate);

        when(occurrenceLookup.exists(previous, nextOperationDate)).thenReturn(false);

        AutoCreationResult result = autoCreationService.createNextOccurrenceIfDue(previous, previousAssignment, today);

        assertNotNull(result);
        assertTrue(result.created());
        assertEquals(nextOperationDate, result.newAssignment().getControlOperationDate());
        assertEquals(nextOperationDate.plusMonths(monthsToAdd), result.newAssignment().getNextControlOperationDate());
    }

    private static Stream<Arguments> frequencyCases() {
        return Stream.of(
                Arguments.of("Monthly", 1),
                Arguments.of("Recurring", 1),
                Arguments.of("Quarterly", 3),
                Arguments.of("Semi-annual", 6),
                Arguments.of("Annual", 12)
        );
    }

    private Control controlWithFrequency(String frequency) {
        Control control = new Control();
        control.setId(10L);
        control.setControlId("CTRL-001");
        control.setControlFrequency(frequency);
        control.setControlStatus("In Progress");
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
            return operationDate.plusDays(15);
        }
        if (normalized.contains("quarterly")) {
            return operationDate.plusDays(45);
        }
        if (normalized.contains("annual") || normalized.contains("semi")) {
            return operationDate.plusDays(90);
        }
        return operationDate.plusDays(30);
    }

    interface OccurrenceLookup {
        boolean exists(Control previousControl, LocalDate operationDate);
    }

    interface AutoCreationService {
        AutoCreationResult createNextOccurrenceIfDue(Control previousControl,
                                                     ControlAssignment previousAssignment,
                                                     LocalDate today);
    }

    record AutoCreationResult(boolean created,
                              Control newControl,
                              ControlAssignment newAssignment) {
    }

    private static class PlaceholderAutoCreationService implements AutoCreationService {
        private final OccurrenceLookup occurrenceLookup;

        private PlaceholderAutoCreationService(OccurrenceLookup occurrenceLookup) {
            this.occurrenceLookup = occurrenceLookup;
        }

        @Override
        public AutoCreationResult createNextOccurrenceIfDue(Control previousControl,
                                                            ControlAssignment previousAssignment,
                                                            LocalDate today) {
            return new AutoCreationResult(false, null, null);
        }
    }
}
