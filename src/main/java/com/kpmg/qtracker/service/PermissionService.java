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
     * The one read rule for a control, for its pages and the API alike ({@link AccessPolicy#readAccess}):
     * the user must see it, and a draft stays closed to users it is only shared with.
     */
    public AccessPolicy.ReadAccess readAccess(Control control, User user, ControlAssignmentDTO assignment) {
        AccessPolicy.ControlFacts facts = controlPermissionService.facts(control, user, assignment);
        if (facts == null) {
            return AccessPolicy.ReadAccess.DENIED;
        }
        return AccessPolicy.readAccess(AccessPolicy.Subject.of(user), facts);
    }

    public AccessPolicy.ReadAccess readAccess(Control control, User user) {
        return readAccess(control, user, null);
    }

    /** The Excel export of one completed control ({@link AccessPolicy#canExportCompletedControl}). */
    public boolean canExportCompletedControl(Control control, User user) {
        return AccessPolicy.canExportCompletedControl(AccessPolicy.Subject.of(user),
                controlPermissionService.facts(control, user, null));
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
        if (readAccess(control, user) != AccessPolicy.ReadAccess.ALLOWED) {
            throw ControlReadDeniedException.forbidden();
        }
        return control;
    }
}
