package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlHistoryEntryDTO;
import com.kpmg.qtracker.dto.FieldChangeDTO;
import com.kpmg.qtracker.entity.AdminAuditLog;
import com.kpmg.qtracker.repository.AdminAuditLogRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.repository.WorkflowHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ControlHistoryServiceCompletedEditTest {

    @Mock
    private ControlRepository controlRepository;
    @Mock
    private ControlAssignmentService controlAssignmentService;
    @Mock
    private AdminAuditLogRepository adminAuditLogRepository;
    @Mock
    private WorkflowHistoryRepository workflowHistoryRepository;
    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private ControlHistoryService service;

    private static AdminAuditLog entry(String description, String fields, String previous, String next) {
        AdminAuditLog log = new AdminAuditLog();
        log.setAdminEmail("soqm@kpmg.com");
        log.setAdminName("SoQM One");
        log.setActionType("EDIT");
        log.setActionDescription(description);
        log.setChangedFields(fields);
        log.setPreviousValues(previous);
        log.setNewValues(next);
        log.setCreatedAt(LocalDateTime.of(2026, 10, 9, 12, 0));
        return log;
    }

    @Test
    void anEditAfterCompletion_isMarked_withItsReason_andTheReasonIsNoFieldRow() {
        when(controlRepository.findById(1L)).thenReturn(Optional.empty());
        when(adminAuditLogRepository.findByControlIdOrderByCreatedAtDesc(1L)).thenReturn(List.of(
                entry("Edit Control - Edited after completion", "[\"PRP\",\"Reason\"]",
                        "{\"PRP\":\"Old\"}", "{\"PRP\":\"New\",\"Reason\":\"Typo\"}"),
                entry("Edit Control", "[\"PRP\"]", "{\"PRP\":\"Older\"}", "{\"PRP\":\"Old\"}")));
        when(workflowHistoryRepository.findByControlIdOrderByCreatedAtDesc(1L)).thenReturn(List.of());

        List<ControlHistoryEntryDTO> history = service.getControlHistory(1L);

        assertThat(history).hasSize(2);
        ControlHistoryEntryDTO marked = history.get(0);
        assertThat(marked.isEditedAfterCompletion()).isTrue();
        assertThat(marked.getReason()).isEqualTo("Typo");
        assertThat(marked.getActorName()).isEqualTo("SoQM One");
        assertThat(marked.getEventName()).isEqualTo("Edit Control");
        assertThat(marked.getFieldChanges()).extracting(FieldChangeDTO::getField).containsExactly("PRP");
        assertThat(marked.getFieldChanges().get(0).getOldValue()).isEqualTo("Old");
        assertThat(marked.getFieldChanges().get(0).getNewValue()).isEqualTo("New");

        ControlHistoryEntryDTO plain = history.get(1);
        assertThat(plain.isEditedAfterCompletion()).isFalse();
        assertThat(plain.getReason()).isNull();
    }
}
