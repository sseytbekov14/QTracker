package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlDetailsDTO;
import com.kpmg.qtracker.entity.ControlDetails;
import com.kpmg.qtracker.repository.ControlDetailsRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ControlDetailsServiceTest {

    @Mock
    private ControlDetailsRepository repository;

    @InjectMocks
    private ControlDetailsService service;

    @Test
    void saveDetails_whenNullFields_doesNotOverwriteExistingValues() {
        Long controlId = 10L;
        ControlDetails existing = new ControlDetails();
        existing.setControlId(controlId);
        existing.setHomogeneity("Homogenous");

        when(repository.findByControlId(controlId)).thenReturn(Optional.of(existing));
        when(repository.save(any(ControlDetails.class))).thenAnswer(invocation -> invocation.getArgument(0));

        ControlDetailsDTO dto = new ControlDetailsDTO();
        dto.setControlId(controlId);

        ControlDetails saved = service.saveDetails(dto);

        assertThat(saved.getHomogeneity()).isEqualTo("Homogenous");
    }
}
