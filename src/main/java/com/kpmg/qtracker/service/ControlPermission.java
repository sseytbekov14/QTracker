package com.kpmg.qtracker.service;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** What one user may do on one control, as {@link AccessPolicy#resolve} works it out. */
public final class ControlPermission {
    public static final String FIELD_CONTROL_STEPS_PERFORMED = "controlStepsPerformed";
    public static final String FIELD_PROCESS_OWNER_COMMENTS = "processOwnerComments";
    /** Control Operator's Program: the Control Operator's own field, written in Review. */
    public static final String FIELD_CONTROL_OPERATOR_REVIEW = "controlOperatorReview";

    private final boolean canView;
    private final boolean canEdit;
    private final Set<String> allowedEditableFields;
    private final boolean canUseWorkflowActions;
    private final boolean canEditAll;
    private final boolean sharedViewer;
    private final boolean facilitator;
    private final boolean controlOperator;
    private final boolean soqmLead;
    private final boolean processOwner;
    private final boolean stepsSplit;
    private final boolean locked;

    public ControlPermission(boolean canView,
                             boolean canEdit,
                             Set<String> allowedEditableFields,
                             boolean canUseWorkflowActions,
                             boolean canEditAll,
                             boolean sharedViewer,
                             boolean facilitator,
                             boolean controlOperator,
                             boolean soqmLead,
                             boolean processOwner) {
        this(canView, canEdit, allowedEditableFields, canUseWorkflowActions, canEditAll, sharedViewer,
                facilitator, controlOperator, soqmLead, processOwner, false);
    }

    /** @param stepsSplit the Facilitator and the Control Operator are different people ({@link ControlStepsFields}) */
    public ControlPermission(boolean canView,
                             boolean canEdit,
                             Set<String> allowedEditableFields,
                             boolean canUseWorkflowActions,
                             boolean canEditAll,
                             boolean sharedViewer,
                             boolean facilitator,
                             boolean controlOperator,
                             boolean soqmLead,
                             boolean processOwner,
                             boolean stepsSplit) {
        this(canView, canEdit, allowedEditableFields, canUseWorkflowActions, canEditAll, sharedViewer,
                facilitator, controlOperator, soqmLead, processOwner, stepsSplit, false);
    }

    /**
     * @param soqmLead the user is SoQM: performs the SoQM steps, acts for the others, renames, reopens
     * @param locked   a completed control: nobody edits it ({@link AccessPolicy#isLocked})
     */
    public ControlPermission(boolean canView,
                             boolean canEdit,
                             Set<String> allowedEditableFields,
                             boolean canUseWorkflowActions,
                             boolean canEditAll,
                             boolean sharedViewer,
                             boolean facilitator,
                             boolean controlOperator,
                             boolean soqmLead,
                             boolean processOwner,
                             boolean stepsSplit,
                             boolean locked) {
        this.canView = canView;
        this.canEdit = canEdit;
        this.allowedEditableFields = Collections.unmodifiableSet(
                allowedEditableFields == null ? Set.of() : new LinkedHashSet<>(allowedEditableFields)
        );
        this.canUseWorkflowActions = canUseWorkflowActions;
        this.canEditAll = canEditAll;
        this.sharedViewer = sharedViewer;
        this.facilitator = facilitator;
        this.controlOperator = controlOperator;
        this.soqmLead = soqmLead;
        this.processOwner = processOwner;
        this.stepsSplit = stepsSplit;
        this.locked = locked;
    }

    public static ControlPermission denied() {
        return new ControlPermission(
                false,
                false,
                Set.of(),
                false,
                false,
                false,
                false,
                false,
                false,
                false
        );
    }

    public boolean canView() {
        return canView;
    }

    public boolean canEdit() {
        return canEdit;
    }

    public Set<String> getAllowedEditableFields() {
        return allowedEditableFields;
    }

    public boolean canUseWorkflowActions() {
        return canUseWorkflowActions;
    }

    public boolean canEditAll() {
        return canEditAll;
    }

    public boolean isSharedViewer() {
        return sharedViewer;
    }

    public boolean isFacilitator() {
        return facilitator;
    }

    public boolean isControlOperator() {
        return controlOperator;
    }

    public boolean isSoqmLead() {
        return soqmLead;
    }

    public boolean isProcessOwner() {
        return processOwner;
    }

    public boolean canEditStepsPerformed() {
        return allowedEditableFields.contains(FIELD_CONTROL_STEPS_PERFORMED);
    }

    public boolean canEditOperatorReview() {
        return allowedEditableFields.contains(FIELD_CONTROL_OPERATOR_REVIEW);
    }

    /** A completed control: no edit by anyone until SoQM returns it ({@link AccessPolicy#LOCKED_MESSAGE}). */
    public boolean isLocked() {
        return locked;
    }

    /** Why the user may not change the control: the completed-control rule first, else the given message. */
    public String editRefusal(String otherwise) {
        return locked && canView ? AccessPolicy.LOCKED_MESSAGE : otherwise;
    }

    /** The Facilitator and the Control Operator are different people ({@link ControlStepsFields#split}). */
    public boolean isStepsSplit() {
        return stepsSplit;
    }

    /** Control Operator's Program must be filled before Submit to SoQM Team ({@link ControlStepsFields}). */
    public boolean isOperatorProgramRequired() {
        return ControlStepsFields.operatorProgramRequired(stepsSplit);
    }

    /** Who saves Control Steps Performed and Results: SoQM, or the participant whose step it is. */
    public boolean canWriteStepsPerformed() {
        return canEditAll || canEditStepsPerformed();
    }

    /**
     * Who saves Control Operator's Program: SoQM (on behalf of the Control Operator, the Changelog names who
     * saved it) or the Control Operator in Review, whether or not the Operator is also a Facilitator.
     */
    public boolean canWriteOperatorReview() {
        return canEditAll || canEditOperatorReview();
    }

    public boolean canEditProcessOwnerComments() {
        return allowedEditableFields.contains(FIELD_PROCESS_OWNER_COMMENTS);
    }
}
