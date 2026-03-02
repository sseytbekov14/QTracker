package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class PermissionService {

    private final ControlPermissionService controlPermissionService;

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
