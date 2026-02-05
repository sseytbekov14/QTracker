package com.kpmg.qtracker.dto;

import com.kpmg.qtracker.enums.WorkflowActionType;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class WorkflowHistoryDTO {
    private Long id;
    private Long controlId;
    private WorkflowActionType actionType;
    private String performedByEmail;
    private String performedByName;
    private String fromStep;
    private String toStep;
    private String comments;
    private LocalDateTime createdAt;

    // Форматированное время для отображения
    public String getFormattedTime() {
        if (createdAt == null) return "";
        return createdAt.format(java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
    }
}