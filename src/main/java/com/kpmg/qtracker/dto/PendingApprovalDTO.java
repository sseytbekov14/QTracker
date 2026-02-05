package com.kpmg.qtracker.dto;

import lombok.Data;
import java.time.LocalDateTime;

@Data
public class PendingApprovalDTO {
    private Long controlId;
    private String controlIdNumber; // Например: "CTRL-2025-001"
    private String component;
    private String controlType;
    private String currentStep; // FACILITATOR, CONTROL_OPERATOR, etc.
    private String stepDisplayName; // "With Facilitator", "With Control Operator"
    private LocalDateTime assignedAt;
    private String assignedToName;
    private String controlDescription;

    // Для отображения в UI
    public String getDaysPending() {
        if (assignedAt == null) return "N/A";
        long days = java.time.temporal.ChronoUnit.DAYS.between(assignedAt.toLocalDate(),
                java.time.LocalDate.now());
        return days + " day" + (days != 1 ? "s" : "");
    }
}