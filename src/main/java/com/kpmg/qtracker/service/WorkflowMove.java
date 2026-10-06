package com.kpmg.qtracker.service;

import com.kpmg.qtracker.enums.WorkflowStatus;

/**
 * One move of a control from its status to another, as {@link AccessPolicy#move} allows it to one user:
 * the role whose step it is ({@code actingFor}), whether a SoQM user makes it for the participant who
 * holds that step ({@code onBehalf}), and the standard transition it is, if any (null for the returns only
 * SoQM makes, e.g. Process Owner Review back to In Progress).
 */
public record WorkflowMove(String from,
                           String to,
                           WorkflowTransition.Actor actingFor,
                           boolean onBehalf,
                           WorkflowTransition transition) {

    /** A standard step, made by its own participant or, for a participant, by SoQM. */
    public static WorkflowMove of(WorkflowTransition transition, boolean onBehalf) {
        return new WorkflowMove(transition.getFromStatus(), transition.getTargetStatus(), transition.getActor(),
                onBehalf, transition);
    }

    /** Back to an earlier status. */
    public boolean isReturn() {
        return AccessPolicy.isReturn(from, to);
    }

    /** Every return and every move SoQM makes for a participant needs a comment ({@link AccessPolicy#moveNeedsComment}). */
    public boolean commentRequired() {
        return AccessPolicy.moveNeedsComment(isReturn(), onBehalf);
    }

    /** "Return to Facilitator", "Submit to SoQM Team", "Return to In Progress", ... */
    public String label() {
        if (transition != null) {
            return transition.getLabel();
        }
        return (isReturn() ? "Return to " : "Move to ") + displayStatus(to);
    }

    public static String displayStatus(String status) {
        try {
            return WorkflowStatus.valueOf(status).getDisplayName();
        } catch (IllegalArgumentException | NullPointerException e) {
            return String.valueOf(status);
        }
    }
}
