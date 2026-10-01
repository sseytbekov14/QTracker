package com.kpmg.qtracker.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Allowed workflow transitions: which status a transition starts from, where it leads
 * and which participant of the control may perform it.
 */
public enum WorkflowTransition {
    INITIATE("Initiate", Actor.COORDINATOR, "DRAFT", "IN_PROGRESS"),
    SUBMIT_TO_CONTROL_OPERATOR("Submit to Control Operator", Actor.FACILITATOR, "IN_PROGRESS", "REVIEW"),
    RETURN_TO_FACILITATOR("Return to Facilitator", Actor.CONTROL_OPERATOR, "REVIEW", "IN_PROGRESS"),
    SUBMIT_TO_SOQM_TEAM("Submit to SoQM Team", Actor.CONTROL_OPERATOR, "REVIEW", "SOQM_HEAD_REVIEW"),
    RETURN_TO_OPERATOR("Return to Control Operator", Actor.SOQM_TEAM, "SOQM_HEAD_REVIEW", "REVIEW"),
    SUBMIT_TO_PROCESS_OWNER("Submit to Process Owner", Actor.SOQM_TEAM, "SOQM_HEAD_REVIEW", "PROCESS_OWNER_REVIEW"),
    COMPLETE("Complete", Actor.PROCESS_OWNER, "PROCESS_OWNER_REVIEW", "COMPLETED"),
    RETURN_TO_SOQM_TEAM("Return to SoQM Team", Actor.PROCESS_OWNER, "PROCESS_OWNER_REVIEW", "SOQM_HEAD_REVIEW"),
    OWNER_RETURN_TO_OPERATOR("Send for Revision to Control Operator", Actor.PROCESS_OWNER, "PROCESS_OWNER_REVIEW", "REVIEW"),
    OWNER_RETURN_TO_FACILITATOR("Return to Facilitator", Actor.PROCESS_OWNER, "PROCESS_OWNER_REVIEW", "IN_PROGRESS"),
    SHARED_RESUBMIT_TO_SOQM_TEAM("Submit completed control to SoQM Team", Actor.SHARED_VIEWER, "COMPLETED", "SOQM_HEAD_REVIEW");

    /** Who may perform a transition, resolved against {@link ControlPermission}. */
    public enum Actor {
        FACILITATOR("Facilitator"),
        CONTROL_OPERATOR("Control Operator"),
        SOQM_TEAM("SoQM Team"),
        PROCESS_OWNER("Process Owner"),
        /** SoQM role, admin or the control's creator. */
        COORDINATOR("SoQM Team or control creator"),
        SHARED_VIEWER("shared viewer");

        private final String displayName;

        Actor(String displayName) {
            this.displayName = displayName;
        }

        public String getDisplayName() {
            return displayName;
        }
    }

    // Action names sent to /api/workflow/perform-action. Initiate is not one of them: it has its own
    // endpoint (POST /api/performance/initiate), which checks the required fields and creates the steps.
    private static final Map<String, List<WorkflowTransition>> ACTIONS = Map.ofEntries(
            Map.entry("SUBMIT_TO_CONTROL_OPERATOR", List.of(SUBMIT_TO_CONTROL_OPERATOR)),
            Map.entry("SUBMIT_FOR_SOQM", List.of(SUBMIT_TO_SOQM_TEAM)),
            Map.entry("SUBMIT_SOQM", List.of(SUBMIT_TO_SOQM_TEAM)),
            Map.entry("RETURN_TO_FACILITATOR", List.of(RETURN_TO_FACILITATOR, OWNER_RETURN_TO_FACILITATOR)),
            Map.entry("SEND_TO_PROCESS_OWNER", List.of(SUBMIT_TO_PROCESS_OWNER)),
            Map.entry("SOQM_COMMENT", List.of(SUBMIT_TO_PROCESS_OWNER)),
            Map.entry("SEND_BACK_TO_OPERATOR", List.of(RETURN_TO_OPERATOR)),
            Map.entry("COMPLETE", List.of(COMPLETE)),
            Map.entry("RETURN_TO_SOQM_TEAM", List.of(RETURN_TO_SOQM_TEAM)),
            Map.entry("SEND_FOR_REVISION", List.of(OWNER_RETURN_TO_OPERATOR)),
            Map.entry("REJECT", List.of(OWNER_RETURN_TO_FACILITATOR))
    );

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

    /** Candidate transitions for a perform-action name; empty when the action is unknown. */
    public static List<WorkflowTransition> forAction(String action) {
        if (action == null) {
            return List.of();
        }
        return ACTIONS.getOrDefault(action.trim().toUpperCase(Locale.ROOT), List.of());
    }
}
