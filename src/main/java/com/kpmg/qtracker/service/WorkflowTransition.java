package com.kpmg.qtracker.service;

import com.kpmg.qtracker.enums.UserRole;

import java.util.Set;

/**
 * Allowed workflow transitions: which status a transition starts from, where it leads
 * and which participant of the control may perform it.
 */
public enum WorkflowTransition {
    INITIATE("Initiate", Actor.COORDINATOR, "DRAFT", "IN_PROGRESS"),
    SUBMIT_TO_CONTROL_OPERATOR("Submit to Control Operator", Actor.FACILITATOR, "IN_PROGRESS", "REVIEW"),
    RETURN_TO_FACILITATOR("Return to Facilitator", Actor.CONTROL_OPERATOR, "REVIEW", "IN_PROGRESS"),
    SUBMIT_TO_SOQM_TEAM("Submit to " + UserRole.SOQM_TEAM_NAME, Actor.CONTROL_OPERATOR, "REVIEW", "SOQM_HEAD_REVIEW"),
    RETURN_TO_OPERATOR("Return to Control Operator", Actor.SOQM_TEAM, "SOQM_HEAD_REVIEW", "REVIEW"),
    SUBMIT_TO_PROCESS_OWNER("Submit to Process Owner", Actor.SOQM_TEAM, "SOQM_HEAD_REVIEW", "PROCESS_OWNER_REVIEW"),
    COMPLETE("Complete", Actor.PROCESS_OWNER, "PROCESS_OWNER_REVIEW", "COMPLETED"),
    OWNER_RETURN_TO_OPERATOR("Return to Control Operator", Actor.PROCESS_OWNER, "PROCESS_OWNER_REVIEW", "REVIEW"),
    /** The Process Owner sends the control back to the start, the Facilitator's step (2026-11-25). */
    OWNER_RETURN_TO_FACILITATOR("Return to Facilitator", Actor.PROCESS_OWNER, "PROCESS_OWNER_REVIEW", "IN_PROGRESS");

    /** Who may perform a transition, resolved against {@link ControlPermission}. */
    public enum Actor {
        FACILITATOR("Facilitator"),
        CONTROL_OPERATOR("Control Operator"),
        /** Any SoQM user, assigned to the control or not. */
        SOQM_TEAM(UserRole.SOQM_TEAM_NAME),
        PROCESS_OWNER("Process Owner"),
        /** Any SoQM user (Initiate). */
        COORDINATOR(UserRole.SOQM_TEAM_NAME);

        private final String displayName;

        Actor(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    /** The returns of spec 9.4; each needs a comment. */
    private static final Set<WorkflowTransition> RETURNS =
            Set.of(RETURN_TO_FACILITATOR, RETURN_TO_OPERATOR, OWNER_RETURN_TO_OPERATOR, OWNER_RETURN_TO_FACILITATOR);

    private final String label;
    private final Actor actor;
    private final String fromStatus;
    private final String targetStatus;

    WorkflowTransition(String label, Actor actor, String fromStatus, String targetStatus) {
        this.label = label;
        this.actor = actor;
        this.fromStatus = fromStatus;
        this.targetStatus = targetStatus;
    }

    public String getLabel() {
        return label;
    }

    public Actor getActor() {
        return actor;
    }

    public String getFromStatus() {
        return fromStatus;
    }

    public String getTargetStatus() {
        return targetStatus;
    }

    /** A return to a previous working step: Control Operator to Facilitator, SoQM or Process Owner to Control Operator. */
    public boolean isReturn() {
        return RETURNS.contains(this);
    }
}
