package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
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

        assertThat(saved.getNextControlOperationDate()).isEqualTo(operationDate.plusMonths(1));
    }

    @Test
    void monthlyNextDateComputedWhenMissing() {
        assertNextOperationDateComputed("Monthly", LocalDate.of(2026, 2, 4),
                null, LocalDate.of(2026, 3, 4));
    }

    @Test
    void quarterlyNextDateComputedWhenMissing() {
        assertNextOperationDateComputed("Quarterly", LocalDate.of(2026, 2, 4),
                null, LocalDate.of(2026, 5, 4));
    }

    @Test
    void semiAnnualNextDateComputedWhenMissing() {
        assertNextOperationDateComputed("Semi-annual", LocalDate.of(2026, 2, 4),
                null, LocalDate.of(2026, 8, 4));
    }

    @Test
    void annualNextDateComputedWhenMissing() {
        assertNextOperationDateComputed("Annual", LocalDate.of(2026, 2, 4),
                null, LocalDate.of(2027, 2, 4));
    }

    @Test
    void invalidNextDateRecomputedAccordingToFrequency() {
        assertNextOperationDateComputed("Quarterly", LocalDate.of(2026, 2, 4),
                LocalDate.of(2026, 2, 4), LocalDate.of(2026, 5, 4));
    }

    private void assertNextOperationDateComputed(String frequency,
                                                 LocalDate operationDate,
                                                 LocalDate providedNextDate,
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
        assertThat(saved.getNextControlOperationDate()).isEqualTo(expectedNextDate);

        verify(controlRepository, never()).save(any(Control.class));
    }
}
