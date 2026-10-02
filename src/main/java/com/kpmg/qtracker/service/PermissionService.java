package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.exception.ControlReadDeniedException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PermissionService {

    private final ControlPermissionService controlPermissionService;
    private final IControlService controlService;

    /** Whether a user may read a control; see {@link #readAccess}. */
    public enum ReadAccess {
        ALLOWED,
        DENIED,
        /** A draft the user is only shared with: it opens for them once it is initiated. */
        DRAFT_NOT_INITIATED
    }

    public ControlPermission resolve(Control control, User user) {
        return controlPermissionService.resolve(control, user);
    }

    public ControlPermission resolve(Control control, User user, ControlAssignmentDTO assignment) {
        return controlPermissionService.resolve(control, user, assignment);
    }

    public boolean canView(Control control, User user) {
        return resolve(control, user).canView();
    }

    public boolean canEdit(Control control, User user) {
        return resolve(control, user).canEdit();
    }

    public boolean canUseWorkflowActions(Control control, User user) {
        return resolve(control, user).canUseWorkflowActions();
    }

    /**
     * The one read rule for a control, for its pages and the API alike: the user must be able to view it,
     * and a draft stays closed to users it is only shared with.
     */
    public ReadAccess readAccess(Control control, User user, ControlPermission permission) {
        if (control == null || user == null || permission == null || !permission.canView()) {
            return ReadAccess.DENIED;
        }
        if (isDraft(control) && isSharedOnly(control, user, permission)) {
            return ReadAccess.DRAFT_NOT_INITIATED;
        }
        return ReadAccess.ALLOWED;
    }

    /**
     * For API reads, called before anything is loaded: the control when {@link #readAccess} allows it,
     * otherwise a {@link ControlReadDeniedException} (401 without a user, 404 for an unknown control, 403).
     */
    public Control requireReadable(Long controlId, User user) {
        if (user == null) {
            throw ControlReadDeniedException.unauthenticated();
        }
        Control control = controlId == null ? null : controlService.getControlById(controlId).orElse(null);
        if (control == null) {
            throw ControlReadDeniedException.notFound();
        }
        if (readAccess(control, user, resolve(control, user)) != ReadAccess.ALLOWED) {
            throw ControlReadDeniedException.forbidden();
        }
        return control;
    }

    public boolean isSharedOnly(Control control, User user) {
        return isSharedOnly(control, user, resolve(control, user));
    }

    public boolean isSharedOnly(Control control, User user, ControlPermission permission) {
        if (control == null || user == null || permission == null) {
            return false;
        }
        if (!permission.isSharedViewer()) {
            return false;
        }
        if (isCreator(control, user)) {
            return false;
        }
        return !(permission.canEditAll()
                || permission.isFacilitator()
                || permission.isControlOperator()
                || permission.isSoqmLead()
                || permission.isProcessOwner());
    }

    private boolean isDraft(Control control) {
        String status = control.getPerformanceStatus();
        return status == null || status.isBlank() || "DRAFT".equalsIgnoreCase(status.trim());
    }

    private boolean isCreator(Control control, User user) {
        if (control.getCreatedBy() == null) {
            return false;
        }
        String creatorEmail = control.getCreatedBy().getMail();
        String userEmail = user.getMail();
        return creatorEmail != null
                && userEmail != null
                && creatorEmail.equalsIgnoreCase(userEmail);
    }
}
