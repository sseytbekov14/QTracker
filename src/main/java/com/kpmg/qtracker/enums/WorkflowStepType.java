package com.kpmg.qtracker.enums;

public enum WorkflowStepType {
    FACILITATOR("Facilitator"),
    CONTROL_OPERATOR("Control Operator"),
    SOQM_TEAM("SOQM Team"),
    PROCESS_OWNER("Process Owner");

    private final String displayName;

    WorkflowStepType(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}