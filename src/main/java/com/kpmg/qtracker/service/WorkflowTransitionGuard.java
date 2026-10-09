package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Server-side check of a workflow move: the user must be the participant the move belongs to, or SoQM
 * acting for that participant ({@link AccessPolicy#isActor}, {@link AccessPolicy#actsOnBehalf}; 403), and
 * the control must be in the move's source status (409).
 */
@Component
public class WorkflowTransitionGuard {

    public static final int BAD_REQUEST = 400;
    public static final int FORBIDDEN = 403;
    public static final int CONFLICT = 409;

    public Decision check(Control control, ControlPermission permission, WorkflowTransition transition) {
        return check(control, permission, List.of(transition));
    }

    /**
     * Picks the candidate the user may perform from the control's current status.
     * Role is checked before status so users outside the workflow learn nothing about its state.
     */
    public Decision check(Control control,
                          ControlPermission permission,
                          Collection<WorkflowTransition> candidates) {
        Decision refused = refuseOutsiders(control, permission);
        if (refused != null) {
            return refused;
        }
        if (candidates == null || candidates.isEmpty()) {
            return Decision.deny(FORBIDDEN, "Forbidden");
        }

        List<WorkflowTransition> permitted = candidates.stream()
                .filter(transition -> AccessPolicy.isActor(transition.getActor(), permission)
                        || AccessPolicy.actsOnBehalf(transition.getActor(), permission))
                .toList();
        WorkflowTransition requested = candidates.iterator().next();
        if (permitted.isEmpty()) {
            return Decision.deny(FORBIDDEN, notActorMessage(requested));
        }

        String status = normalizeStatus(control.getPerformanceStatus());
        Optional<WorkflowTransition> match = permitted.stream()
                .filter(transition -> transition.getFromStatus().equals(status))
                .findFirst();
        if (match.isEmpty()) {
            return Decision.deny(CONFLICT, "\"" + permitted.get(0).getLabel()
                    + "\" is not allowed while the control is in status " + WorkflowMove.displayStatus(status));
        }
        WorkflowTransition transition = match.get();
        return Decision.allow(WorkflowMove.of(transition, !AccessPolicy.isActor(transition.getActor(), permission)));
    }

    /**
     * A move to a target status ({@code POST /api/workflow/move}): a step of the user's own, or as SoQM any
     * move of {@link AccessPolicy#soqmTargets} from the control's status.
     */
    public Decision checkMove(Control control, ControlPermission permission, String targetStatus) {
        Decision refused = refuseOutsiders(control, permission);
        if (refused != null) {
            return refused;
        }
        if (targetStatus == null || targetStatus.isBlank() || !AccessPolicy.isWorkflowStatus(targetStatus)) {
            return Decision.deny(BAD_REQUEST, "Unknown status: " + targetStatus);
        }
        String status = normalizeStatus(control.getPerformanceStatus());
        String target = normalizeStatus(targetStatus);
        Optional<WorkflowMove> move = AccessPolicy.move(permission, status, target);
        if (move.isPresent()) {
            return Decision.allow(move.get());
        }
        if (!permission.isSoqmLead() && !permission.isFacilitator() && !permission.isControlOperator()
                && !permission.isProcessOwner()) {
            return Decision.deny(FORBIDDEN, "Only SoQM Team or the person assigned to this step can move this control");
        }
        return Decision.deny(CONFLICT, "The control cannot be moved from " + WorkflowMove.displayStatus(status)
                + " to " + WorkflowMove.displayStatus(target) + " by you");
    }

    private Decision refuseOutsiders(Control control, ControlPermission permission) {
        if (control == null || permission == null || !permission.canView()) {
            return Decision.deny(FORBIDDEN, "Forbidden");
        }
        if (!permission.canUseWorkflowActions()) {
            return Decision.deny(FORBIDDEN, "You have read-only access to this control");
        }
        return null;
    }

    private String notActorMessage(WorkflowTransition transition) {
        return switch (transition.getActor()) {
            case SOQM_TEAM, COORDINATOR -> "Only SoQM Team can perform \"" + transition.getLabel() + "\" on this control";
            default -> "Only the assigned " + transition.getActor().getDisplayName()
                    + " or SoQM can perform \"" + transition.getLabel() + "\" on this control";
        };
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "DRAFT";
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }

    /** Outcome of a check: the move to apply, or the HTTP status and message to reject with. */
    public record Decision(WorkflowMove move, int httpStatus, String message) {

        static Decision allow(WorkflowMove move) {
            return new Decision(move, 200, null);
        }

        static Decision deny(int httpStatus, String message) {
            return new Decision(null, httpStatus, message);
        }

        public boolean allowed() {
            return move != null;
        }

        /** The standard transition of the move, or null (refused, or a return only SoQM makes). */
        public WorkflowTransition transition() {
            return move != null ? move.transition() : null;
        }
    }
}
