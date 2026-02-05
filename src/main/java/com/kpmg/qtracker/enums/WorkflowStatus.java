package com.kpmg.qtracker.enums;

public enum WorkflowStatus {
    NOT_STARTED("Not Started"),        // ★ ДОБАВЬТЕ ЭТУ СТРОЧКУ
    DRAFT("Draft"),
    FACILITATOR_REVIEW("With Facilitator"),
    CONTROL_OPERATOR_REVIEW("With Control Operator"),
    SOQM_LEAD_REVIEW("With SOQM Lead"),
    PROCESS_OWNER_REVIEW("With Process Owner"),
    COMPLETED("Completed"),
    RETURNED("Returned for Revision"),
    REJECTED("Rejected");

    private final String displayName;

    WorkflowStatus(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}