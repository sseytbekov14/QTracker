package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.support.TestUsers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ControlAssignmentServiceTest {

    @Mock
    private ControlAssignmentRepository assignmentRepository;
    @Mock
    private UserRepository userRepository;
    @Mock
    private ControlRepository controlRepository;
    @Spy
    private ControlScheduleCalculator scheduleCalculator = new ControlScheduleCalculator();

    @InjectMocks
    private ControlAssignmentService service;

    @Test
    void normalizesNextControlOperationDateWhenInvalid() {
        Long controlId = 55L;
        LocalDate operationDate = LocalDate.of(2026, 2, 10);

        ControlAssignmentDTO dto = new ControlAssignmentDTO();
        dto.setControlId(controlId);
        dto.setControlOperationDate(operationDate);
        dto.setNextControlOperationDate(operationDate.minusDays(1)); // invalid

        Control control = new Control();
        control.setId(controlId);
        control.setControlFrequency("Monthly");

        when(assignmentRepository.findByControlId(controlId)).thenReturn(Optional.empty());
        when(controlRepository.findById(controlId)).thenReturn(Optional.of(control));
        when(assignmentRepository.save(any(ControlAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        ControlAssignment saved = service.saveAssignment(dto);

        assertThat(saved.getControlOperationDeadline()).isEqualTo(operationDate.plusDays(14));
        assertThat(saved.getNextControlOperationDate()).isEqualTo(operationDate.plusMonths(1));

        ArgumentCaptor<Control> controlCaptor = ArgumentCaptor.forClass(Control.class);
        verify(controlRepository).save(controlCaptor.capture());
        assertThat(controlCaptor.getValue().getDeadline()).isEqualTo(operationDate.plusDays(14));
    }

    @Test
    void saveAssignment_keepingTheOperationDate_keepsTheStoredDeadlineAndNextDate() {
        // A Monthly control set up under the earlier rule (deadline + 7 days): reassigning people does not move it
        LocalDate operationDate = LocalDate.of(2026, 9, 25);
        ControlAssignment stored = storedAssignment(81L, operationDate, LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 25));
        Control control = control(81L, "Monthly");
        when(assignmentRepository.findByControlId(81L)).thenReturn(Optional.of(stored));
        when(controlRepository.findById(81L)).thenReturn(Optional.of(control));
        when(assignmentRepository.save(any(ControlAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        ControlAssignmentDTO dto = dto(81L, List.of(), null, null);
        dto.setControlOperationDate(operationDate);
        dto.setControlOperationDeadline(LocalDate.of(2030, 1, 1));       // whatever the page sends is ignored
        dto.setNextControlOperationDate(LocalDate.of(2030, 2, 1));

        ControlAssignment saved = service.saveAssignment(dto);

        assertThat(saved.getControlOperationDeadline()).isEqualTo(LocalDate.of(2026, 10, 2));
        assertThat(saved.getNextControlOperationDate()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(control.getDeadline()).isEqualTo(LocalDate.of(2026, 10, 2));
    }

    @Test
    void saveAssignment_keepingTheSchedule_keepsEveryStoredDate_evenWithoutADeadline_andLeavesTheControlAlone() {
        // A completed control changed in place: its people change, its dates do not (another date sent is ignored)
        LocalDate operationDate = LocalDate.of(2026, 9, 25);
        ControlAssignment stored = storedAssignment(86L, operationDate, null, LocalDate.of(2026, 10, 25));
        Control control = control(86L, "Monthly");
        when(assignmentRepository.findByControlId(86L)).thenReturn(Optional.of(stored));
        when(controlRepository.findById(86L)).thenReturn(Optional.of(control));
        when(assignmentRepository.save(any(ControlAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        ControlAssignmentDTO dto = dto(86L, List.of(), null, null);
        dto.setControlOperationDate(LocalDate.of(2026, 12, 1));
        dto.setControlOperationDeadline(LocalDate.of(2030, 1, 1));

        ControlAssignment saved = service.saveAssignment(dto, true);

        assertThat(saved.getControlOperationDate()).isEqualTo(operationDate);
        assertThat(saved.getControlOperationDeadline()).isNull();
        assertThat(saved.getNextControlOperationDate()).isEqualTo(LocalDate.of(2026, 10, 25));
        verify(scheduleCalculator, never()).calculateDeadline(any(), any());
        verify(controlRepository, never()).save(any(Control.class));
    }

    @Test
    void saveAssignment_withoutADate_keepsTheStoredSchedule() {
        LocalDate operationDate = LocalDate.of(2026, 9, 25);
        ControlAssignment stored = storedAssignment(82L, operationDate, LocalDate.of(2026, 10, 25), null);
        when(assignmentRepository.findByControlId(82L)).thenReturn(Optional.of(stored));
        when(controlRepository.findById(82L)).thenReturn(Optional.of(control(82L, "Annual")));
        when(assignmentRepository.save(any(ControlAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        ControlAssignment saved = service.saveAssignment(dto(82L, List.of(), null, null));

        assertThat(saved.getControlOperationDate()).isEqualTo(operationDate);
        assertThat(saved.getControlOperationDeadline()).isEqualTo(LocalDate.of(2026, 10, 25));
        assertThat(saved.getNextControlOperationDate()).isNull();
    }

    @Test
    void saveAssignment_changingTheOperationDate_appliesTheFourteenDays() {
        ControlAssignment stored = storedAssignment(83L, LocalDate.of(2026, 9, 25),
                LocalDate.of(2026, 10, 25), LocalDate.of(2027, 9, 25));
        Control control = control(83L, "Annual");
        when(assignmentRepository.findByControlId(83L)).thenReturn(Optional.of(stored));
        when(controlRepository.findById(83L)).thenReturn(Optional.of(control));
        when(assignmentRepository.save(any(ControlAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        ControlAssignmentDTO dto = dto(83L, List.of(), null, null);
        dto.setControlOperationDate(LocalDate.of(2026, 10, 1));
        dto.setControlOperationDeadline(LocalDate.of(2026, 10, 2));

        ControlAssignment saved = service.saveAssignment(dto);

        assertThat(saved.getControlOperationDeadline()).isEqualTo(LocalDate.of(2026, 10, 15));
        assertThat(saved.getNextControlOperationDate()).isEqualTo(LocalDate.of(2027, 10, 1));
        assertThat(control.getDeadline()).isEqualTo(LocalDate.of(2026, 10, 15));
    }

    @Test
    void saveAssignment_storedDateWithoutADeadline_getsOne() {
        ControlAssignment stored = storedAssignment(84L, LocalDate.of(2026, 9, 25), null, null);
        when(assignmentRepository.findByControlId(84L)).thenReturn(Optional.of(stored));
        when(controlRepository.findById(84L)).thenReturn(Optional.of(control(84L, "Quarterly")));
        when(assignmentRepository.save(any(ControlAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        ControlAssignment saved = service.saveAssignment(dto(84L, List.of(), null, null));

        assertThat(saved.getControlOperationDeadline()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(saved.getNextControlOperationDate()).isEqualTo(LocalDate.of(2026, 12, 25));
    }

    @Test
    void recalculateSchedule_afterAFrequencyChange_appliesTheFourteenDays() {
        ControlAssignment stored = storedAssignment(85L, LocalDate.of(2026, 9, 25),
                LocalDate.of(2026, 10, 2), LocalDate.of(2026, 10, 25));
        Control control = control(85L, "Semi Annual");
        when(assignmentRepository.findByControlId(85L)).thenReturn(Optional.of(stored));
        when(controlRepository.findById(85L)).thenReturn(Optional.of(control));

        service.recalculateSchedule(85L);

        assertThat(stored.getControlOperationDeadline()).isEqualTo(LocalDate.of(2026, 10, 9));
        assertThat(stored.getNextControlOperationDate()).isEqualTo(LocalDate.of(2027, 3, 25));
        assertThat(control.getDeadline()).isEqualTo(LocalDate.of(2026, 10, 9));
    }

    private static ControlAssignment storedAssignment(Long controlId, LocalDate operationDate,
                                                      LocalDate deadline, LocalDate nextDate) {
        ControlAssignment assignment = new ControlAssignment();
        assignment.setControlId(controlId);
        assignment.setControlOperationDate(operationDate);
        assignment.setControlOperationDeadline(deadline);
        assignment.setNextControlOperationDate(nextDate);
        return assignment;
    }

    private static Control control(Long id, String frequency) {
        Control control = new Control();
        control.setId(id);
        control.setControlId("HR-" + id);
        control.setControlFrequency(frequency);
        return control;
    }

    @Test
    void monthlyNextDateComputedWhenMissing() {
        assertNextOperationDateComputed("Monthly", LocalDate.of(2026, 2, 4),
                null, LocalDate.of(2026, 2, 18), LocalDate.of(2026, 3, 4));
    }

    @Test
    void quarterlyNextDateComputedWhenMissing() {
        assertNextOperationDateComputed("Quarterly", LocalDate.of(2026, 2, 4),
                null, LocalDate.of(2026, 2, 18), LocalDate.of(2026, 5, 4));
    }

    @Test
    void semiAnnualNextDateComputedWhenMissing() {
        assertNextOperationDateComputed("Semi Annual", LocalDate.of(2026, 2, 4),
                null, LocalDate.of(2026, 2, 18), LocalDate.of(2026, 8, 4));
    }

    @Test
    void annualNextDateComputedWhenMissing() {
        assertNextOperationDateComputed("Annual", LocalDate.of(2026, 2, 4),
                null, LocalDate.of(2026, 2, 18), LocalDate.of(2027, 2, 4));
    }

    @Test
    void invalidNextDateRecomputedAccordingToFrequency() {
        assertNextOperationDateComputed("Quarterly", LocalDate.of(2026, 2, 4),
                LocalDate.of(2026, 2, 4), LocalDate.of(2026, 2, 18), LocalDate.of(2026, 5, 4));
    }

    @Test
    void getAssignmentByControlId_splitsSharedWithOnCommaAndSemicolon() {
        Long controlId = 44L;
        ControlAssignment assignment = new ControlAssignment();
        assignment.setControlId(controlId);
        assignment.setControlSharedWith("a@kpmg.kz; b@kpmg.kz, c@kpmg.kz");

        when(assignmentRepository.findByControlId(controlId)).thenReturn(Optional.of(assignment));

        ControlAssignmentDTO dto = service.getAssignmentByControlId(controlId);

        assertThat(dto.getControlSharedWith())
                .containsExactly("a@kpmg.kz", "b@kpmg.kz", "c@kpmg.kz");
    }

    @Test
    void saveAssignment_refusesPeopleTheFieldDoesNotAccept_beforeSavingAnything() {
        Control control = new Control();
        control.setId(90L);
        control.setControlId("HR-90");
        when(controlRepository.findById(90L)).thenReturn(Optional.of(control));
        when(userRepository.findByMail("ro@kpmg.kz")).thenReturn(Optional.of(
                TestUsers.user("ro@kpmg.kz", AccessLevel.READ_ONLY, AccessScope.OWN, false)));
        when(userRepository.findByMail("kdn@kpmg.kz")).thenReturn(Optional.of(
                TestUsers.user("kdn@kpmg.kz", AccessLevel.READ_ONLY, AccessScope.KDN, false)));
        when(userRepository.findByMail("soqm@kpmg.kz")).thenReturn(Optional.of(
                TestUsers.user("soqm@kpmg.kz", AccessLevel.SOQM, AccessScope.ALL, false)));

        assertRefused(dto(90L, List.of("ro@kpmg.kz"), null, null), "Facilitator: ro@kpmg.kz has Read Only access");
        assertRefused(dto(90L, List.of("kdn@kpmg.kz"), null, null), "Facilitator: kdn@kpmg.kz sees only KDN controls");
        assertRefused(dto(90L, List.of("soqm@kpmg.kz"), null, null), "Facilitator: soqm@kpmg.kz is SoQM Team");
        assertRefused(dto(90L, null, List.of("nobody@kpmg.kz"), null), "SoQM Team / Delegate: nobody@kpmg.kz is not a QTracker user");
        verify(assignmentRepository, never()).save(any(ControlAssignment.class));
    }

    @Test
    void saveAssignment_acceptsAKdnUserInTheStepFieldsOfAKdnControl_andAReadOnlySharedUser() {
        Control control = new Control();
        control.setId(91L);
        control.setControlId("KDN-91");
        when(controlRepository.findById(91L)).thenReturn(Optional.of(control));
        when(userRepository.findByMail("kdn@kpmg.kz")).thenReturn(Optional.of(
                TestUsers.user("kdn@kpmg.kz", AccessLevel.READ_ONLY, AccessScope.KDN, false)));
        when(userRepository.findByMail("ro@kpmg.kz")).thenReturn(Optional.of(
                TestUsers.user("ro@kpmg.kz", AccessLevel.READ_ONLY, AccessScope.OWN, false)));
        when(assignmentRepository.findByControlId(91L)).thenReturn(Optional.empty());
        when(assignmentRepository.save(any(ControlAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        ControlAssignmentDTO dto = dto(91L, List.of("kdn@kpmg.kz"), null, List.of("ro@kpmg.kz"));
        dto.setControlOperator(List.of("kdn@kpmg.kz"));

        ControlAssignment saved = service.saveAssignment(dto);

        assertThat(saved.getFacilitator()).isEqualTo("kdn@kpmg.kz");
        assertThat(saved.getControlOperator()).isEqualTo("kdn@kpmg.kz");
        assertThat(saved.getControlSharedWith()).isEqualTo("ro@kpmg.kz");
    }

    @ParameterizedTest(name = "[{0}] KDN control: {1}")
    @CsvSource(value = {
            "KDN-001|true", "KDN001|true", "kdn-5|true", "'  KDN-9  '|true",
            "X-KDN-12|false", "HR-001|false", "KD-N-1|false", "''|false"}, delimiter = '|')
    void saveAssignment_kdnUserInAStepField_onlyWhereTheIdStartsWithKdn(String controlId, boolean kdnControl) {
        Control control = new Control();
        control.setId(92L);
        control.setControlId(controlId);
        when(controlRepository.findById(92L)).thenReturn(Optional.of(control));
        when(userRepository.findByMail("kdn@kpmg.kz")).thenReturn(Optional.of(
                TestUsers.user("kdn@kpmg.kz", AccessLevel.READ_ONLY, AccessScope.KDN, false)));
        lenient().when(assignmentRepository.findByControlId(92L)).thenReturn(Optional.empty());
        lenient().when(assignmentRepository.save(any(ControlAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        ControlAssignmentDTO dto = dto(92L, null, null, null);
        dto.setProcessOwner(List.of("kdn@kpmg.kz"));
        if (kdnControl) {
            assertThat(service.saveAssignment(dto).getProcessOwner()).isEqualTo("kdn@kpmg.kz");
        } else {
            assertRefused(dto, "Process Owner: kdn@kpmg.kz sees only KDN controls");
            verify(assignmentRepository, never()).save(any(ControlAssignment.class));
        }
    }

    private ControlAssignmentDTO dto(Long controlId, List<String> facilitator, List<String> soqmLead,
                                     List<String> sharedWith) {
        ControlAssignmentDTO dto = new ControlAssignmentDTO();
        dto.setControlId(controlId);
        dto.setFacilitator(facilitator);
        dto.setSoqmLead(soqmLead);
        dto.setControlSharedWith(sharedWith);
        return dto;
    }

    private void assertRefused(ControlAssignmentDTO dto, String message) {
        assertThatThrownBy(() -> service.saveAssignment(dto))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageStartingWith(message);
    }

    private void assertNextOperationDateComputed(String frequency,
                                                 LocalDate operationDate,
                                                 LocalDate providedNextDate,
                                                 LocalDate expectedDeadline,
                                                 LocalDate expectedNextDate) {
        Long controlId = 77L;

        ControlAssignmentDTO dto = new ControlAssignmentDTO();
        dto.setControlId(controlId);
        dto.setControlOperationDate(operationDate);
        dto.setNextControlOperationDate(providedNextDate);

        Control control = new Control();
        control.setId(controlId);
        control.setControlFrequency(frequency);

        when(assignmentRepository.findByControlId(controlId)).thenReturn(Optional.empty());
        when(controlRepository.findById(controlId)).thenReturn(Optional.of(control));
        when(assignmentRepository.save(any(ControlAssignment.class))).thenAnswer(inv -> inv.getArgument(0));

        ControlAssignment saved = service.saveAssignment(dto);

        assertThat(saved.getControlId()).isEqualTo(controlId);
        assertThat(saved.getControlOperationDate()).isEqualTo(operationDate);
        assertThat(saved.getControlOperationDeadline()).isEqualTo(expectedDeadline);
        assertThat(saved.getNextControlOperationDate()).isEqualTo(expectedNextDate);

        ArgumentCaptor<Control> controlCaptor = ArgumentCaptor.forClass(Control.class);
        verify(controlRepository).save(controlCaptor.capture());
        assertThat(controlCaptor.getValue().getDeadline()).isEqualTo(expectedDeadline);
    }
}

