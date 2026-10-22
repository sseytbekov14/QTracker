package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.util.StatusDisplayMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Two requests create a control with the same Control ID at the same moment: both pass the check, the second insert
 * hits the unique key. It gets the same refusal as the check (409 DUPLICATE_ID in ControlController), not a
 * database error; any other integrity failure stays one.
 */
@ExtendWith(MockitoExtension.class)
class ControlServiceDuplicateIdTest {

    private static final String ID = "HR-CTRL-1/FY27/Central";

    @Mock
    private ControlRepository controlRepository;

    @Mock
    private UserService userService;

    @Mock
    private UserRepository userRepository;

    @Mock
    private ControlAssignmentRepository controlAssignmentRepository;

    @Mock
    private StatusDisplayMapper statusDisplayMapper;

    private ControlService controlService;

    @BeforeEach
    void setUp() {
        controlService = new ControlService(controlRepository, userService, userRepository,
                controlAssignmentRepository, statusDisplayMapper);
    }

    @Test
    void theIdTakenAtTheSameMoment_isTheDuplicateRefusal() {
        when(controlRepository.existsByControlId(ID)).thenReturn(false, true);
        when(controlRepository.save(any(Control.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key value violates unique constraint"));

        assertThatThrownBy(() -> controlService.createControl(control()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Control ID '" + ID + "' already exists in the system. Please use a different ID.");
    }

    @Test
    void anotherIntegrityFailure_isNotCalledADuplicate() {
        when(controlRepository.existsByControlId(ID)).thenReturn(false, false);
        when(controlRepository.save(any(Control.class)))
                .thenThrow(new DataIntegrityViolationException("value too long for type character varying(255)"));

        assertThatThrownBy(() -> controlService.createControl(control()))
                .isNotInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("value too long");
    }

    private static Control control() {
        Control control = new Control();
        control.setControlId(ID);
        return control;
    }
}
