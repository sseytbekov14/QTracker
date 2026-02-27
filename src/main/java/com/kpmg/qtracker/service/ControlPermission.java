package com.kpmg.qtracker.service;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public final class ControlPermission {
    public static final String FIELD_CONTROL_STEPS_PERFORMED = "controlStepsPerformed";
    public static final String FIELD_PROCESS_OWNER_COMMENTS = "processOwnerComments";

    private final boolean canView;
    private final boolean canEdit;
    private final Set<String> allowedEditableFields;
    private final boolean canUseWorkflowActions;
    private final boolean canEditAll;
    private final boolean sharedViewer;
    private final boolean sharedCompleted;
    private final boolean facilitator;
    private final boolean controlOperator;
    private final boolean soqmLead;
    private final boolean processOwner;

    public ControlPermission(boolean canView,
                             boolean canEdit,
                             Set<String> allowedEditableFields,
                             boolean canUseWorkflowActions,
                             boolean canEditAll,
                             boolean sharedViewer,
                             boolean sharedCompleted,
                             boolean facilitator,
                             boolean controlOperator,
                             boolean soqmLead,
                             boolean processOwner) {
        this.canView = canView;
        this.canEdit = canEdit;
        this.allowedEditableFields = Collections.unmodifiableSet(
                allowedEditableFields == null ? Set.of() : new LinkedHashSet<>(allowedEditableFields)
        );
        this.canUseWorkflowActions = canUseWorkflowActions;
        this.canEditAll = canEditAll;
        this.sharedViewer = sharedViewer;
        this.sharedCompleted = sharedCompleted;
        this.facilitator = facilitator;
        this.controlOperator = controlOperator;
        this.soqmLead = soqmLead;
        this.processOwner = processOwner;
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

    public boolean isSharedCompleted() {
        return sharedCompleted;
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

    public boolean canEditProcessOwnerComments() {
        return allowedEditableFields.contains(FIELD_PROCESS_OWNER_COMMENTS);
    }
}
