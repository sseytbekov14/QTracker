package com.kpmg.qtracker.dto;

import com.kpmg.qtracker.enums.WorkflowStatus;
import lombok.Data;

@Data
public class WorkflowStatusDTO {
    private WorkflowStatus currentStatus;
    private String currentStep;
    private String assignedToEmail;
    private String assignedToName;
    private boolean canEdit;
    private boolean canApprove;
    private boolean canReturn;
    private boolean isCompleted;
    private boolean isReturned;

    // Для отображения
    public String getStatusDisplay() {
        return currentStatus != null ? currentStatus.getDisplayName() : "Unknown";
    }

    public String getStatusColor() {
        if (currentStatus == null) return "secondary";

        switch (currentStatus) {
            case DRAFT: return "secondary";
            case FACILITATOR_REVIEW: return "warning";
            case CONTROL_OPERATOR_REVIEW: return "info";
            case SOQM_LEAD_REVIEW: return "primary";
            case PROCESS_OWNER_REVIEW: return "dark";
            case COMPLETED: return "success";
            case RETURNED: return "danger";
            case REJECTED: return "danger";
            default: return "secondary";
        }
    }
}