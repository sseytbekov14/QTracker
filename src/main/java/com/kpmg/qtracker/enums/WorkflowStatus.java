package com.kpmg.qtracker.enums;

public enum WorkflowStatus {
    DRAFT("Draft"),
    IN_PROGRESS("In Progress"),
    REVIEW("Review"),
    SOQM_HEAD_REVIEW("SoQM Head Review"),
    PROCESS_OWNER_REVIEW("Process Owner Review"),
    COMPLETED("Completed");

    private final String displayName;

    WorkflowStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
