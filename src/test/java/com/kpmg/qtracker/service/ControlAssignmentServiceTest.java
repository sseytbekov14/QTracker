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

        assertThat(saved.getControlOperationDeadline()).isEqualTo(operationDate.plusDays(7));
        assertThat(saved.getNextControlOperationDate()).isEqualTo(operationDate.plusMonths(1));

        ArgumentCaptor<Control> controlCaptor = ArgumentCaptor.forClass(Control.class);
        verify(controlRepository).save(controlCaptor.capture());
        assertThat(controlCaptor.getValue().getDeadline()).isEqualTo(operationDate.plusDays(7));
    }

    @Test
    void monthlyNextDateComputedWhenMissing() {
        assertNextOperationDateComputed("Monthly", LocalDate.of(2026, 2, 4),
                null, LocalDate.of(2026, 2, 11), LocalDate.of(2026, 3, 4));
    }

    @Test
    void quarterlyNextDateComputedWhenMissing() {
        assertNextOperationDateComputed("Quarterly", LocalDate.of(2026, 2, 4),
                null, LocalDate.of(2026, 2, 18), LocalDate.of(2026, 5, 4));
    }

    @Test
    void semiAnnualNextDateComputedWhenMissing() {
        assertNextOperationDateComputed("Semi Annual", LocalDate.of(2026, 2, 4),
                null, LocalDate.of(2026, 3, 4), LocalDate.of(2026, 8, 4));
    }

    @Test
    void annualNextDateComputedWhenMissing() {
        assertNextOperationDateComputed("Annual", LocalDate.of(2026, 2, 4),
                null, LocalDate.of(2026, 3, 4), LocalDate.of(2027, 2, 4));
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
                TestUsers.user("kdn@kpmg.kz", AccessLevel.PARTICIPANT, AccessScope.KDN, false)));
        when(userRepository.findByMail("soqm@kpmg.kz")).thenReturn(Optional.of(
                TestUsers.user("soqm@kpmg.kz", AccessLevel.SOQM, AccessScope.ALL, false)));

        assertRefused(dto(90L, List.of("ro@kpmg.kz"), null, null), "Facilitator: ro@kpmg.kz has read-only access");
        assertRefused(dto(90L, List.of("kdn@kpmg.kz"), null, null), "Facilitator: kdn@kpmg.kz sees only KDN controls");
        assertRefused(dto(90L, List.of("soqm@kpmg.kz"), null, null), "Facilitator: soqm@kpmg.kz is a SoQM user");
        assertRefused(dto(90L, null, List.of("nobody@kpmg.kz"), null), "SoQM Team / Delegate: nobody@kpmg.kz is not a QTracker user");
        verify(assignmentRepository, never()).save(any(ControlAssignment.class));
    }

    @Test
    void saveAssignment_acceptsAKdnParticipantOnAKdnControl_andAReadOnlySharedUser() {
        Control control = new Control();
        control.setId(91L);
        control.setControlId("KDN-91");
        when(controlRepository.findById(91L)).thenReturn(Optional.of(control));
        when(userRepository.findByMail("kdn@kpmg.kz")).thenReturn(Optional.of(
                TestUsers.user("kdn@kpmg.kz", AccessLevel.PARTICIPANT, AccessScope.KDN, false)));
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

