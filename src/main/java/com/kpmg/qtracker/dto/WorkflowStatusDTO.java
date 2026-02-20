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
            case IN_PROGRESS: return "warning";
            case REVIEW: return "info";
            case SOQM_HEAD_REVIEW: return "primary";
            case PROCESS_OWNER_REVIEW: return "dark";
            case COMPLETED: return "success";
            default: return "secondary";
        }
    }
}
