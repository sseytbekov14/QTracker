package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.WorkflowStatus;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Server-side check of a workflow transition: the user must be the participant the
 * transition belongs to (403) and the control must be in the transition's source status (409).
 */
@Component
public class WorkflowTransitionGuard {

    public static final int FORBIDDEN = 403;
    public static final int CONFLICT = 409;

    public Decision check(Control control, User user, ControlPermission permission, WorkflowTransition transition) {
        return check(control, user, permission, List.of(transition));
    }

    /**
     * Picks the candidate the user may perform from the control's current status.
     * Role is checked before status so users outside the workflow learn nothing about its state.
     */
    public Decision check(Control control,
                          User user,
                          ControlPermission permission,
                          Collection<WorkflowTransition> candidates) {
        if (control == null || permission == null || !permission.canView()) {
            return Decision.deny(FORBIDDEN, "Forbidden");
        }
        if (!permission.canUseWorkflowActions()) {
            return Decision.deny(FORBIDDEN, "Workflow actions are disabled for shared users on completed controls");
        }
        if (candidates == null || candidates.isEmpty()) {
            return Decision.deny(FORBIDDEN, "Forbidden");
        }

        List<WorkflowTransition> permitted = candidates.stream()
                .filter(transition -> isActor(transition.getActor(), control, user, permission))
                .toList();
        WorkflowTransition requested = candidates.iterator().next();
        if (permitted.isEmpty()) {
            return Decision.deny(FORBIDDEN, "Only the assigned " + requested.getActor().getDisplayName()
                    + " can perform \"" + requested.getLabel() + "\" on this control");
        }

        String status = normalizeStatus(control.getPerformanceStatus());
        Optional<WorkflowTransition> match = permitted.stream()
                .filter(transition -> transition.getFromStatus().equals(status))
                .findFirst();
        if (match.isEmpty()) {
            return Decision.deny(CONFLICT, "\"" + permitted.get(0).getLabel()
                    + "\" is not allowed while the control is in status " + displayStatus(status));
        }
        return Decision.allow(match.get());
    }

    private boolean isActor(WorkflowTransition.Actor actor, Control control, User user, ControlPermission permission) {
        return switch (actor) {
            case FACILITATOR -> permission.isFacilitator();
            case CONTROL_OPERATOR -> permission.isControlOperator();
            case SOQM_TEAM -> permission.isSoqmLead();
            case PROCESS_OWNER -> permission.isProcessOwner();
            case COORDINATOR -> permission.canEditAll() || isCreator(control, user);
            case SHARED_VIEWER -> permission.isSharedViewer();
        };
    }

    private boolean isCreator(Control control, User user) {
        if (user == null || user.getMail() == null || control.getCreatedBy() == null
                || control.getCreatedBy().getMail() == null) {
            return false;
        }
        return control.getCreatedBy().getMail().trim().equalsIgnoreCase(user.getMail().trim());
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "DRAFT";
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }

    private String displayStatus(String status) {
        try {
            return WorkflowStatus.valueOf(status).getDisplayName();
        } catch (IllegalArgumentException e) {
            return status;
        }
    }

    /** Outcome of a check: the transition to apply, or the HTTP status and message to reject with. */
    public record Decision(WorkflowTransition transition, int httpStatus, String message) {

        static Decision allow(WorkflowTransition transition) {
            return new Decision(transition, 200, null);
        }

        static Decision deny(int httpStatus, String message) {
            return new Decision(null, httpStatus, message);
        }

        public boolean allowed() {
            return transition != null;
        }
    }
}
