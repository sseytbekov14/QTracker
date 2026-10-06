package com.kpmg.qtracker.service;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

/** What one user may do on one control, as {@link AccessPolicy#resolve} works it out. */
public final class ControlPermission {
    public static final String FIELD_CONTROL_STEPS_PERFORMED = "controlStepsPerformed";
    public static final String FIELD_PROCESS_OWNER_COMMENTS = "processOwnerComments";
    /** Control Operator Review and Results: only when the Facilitator and the Operator differ. */
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

    /** @param stepsSplit the control has two steps fields ({@link ControlStepsFields}) */
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

    /** Two steps fields: the Facilitator's and Control Operator Review and Results. */
    public boolean isStepsSplit() {
        return stepsSplit;
    }

    /** Who saves Control Steps Performed and Results: SoQM, or the participant whose step it is. */
    public boolean canWriteStepsPerformed() {
        return canEditAll || canEditStepsPerformed();
    }

    /**
     * Who saves Control Operator Review and Results: SoQM or the Control Operator in Review, and only while
     * the control has two fields; with one person the field is not shown and nobody writes it.
     */
    public boolean canWriteOperatorReview() {
        return stepsSplit && (canEditAll || canEditOperatorReview());
    }

    public boolean canEditProcessOwnerComments() {
        return allowedEditableFields.contains(FIELD_PROCESS_OWNER_COMMENTS);
    }
}
