package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class ControlPermissionService {

    private final IControlService controlService;
    private final ControlAssignmentService controlAssignmentService;

    public ControlPermission resolve(Long controlId, User user) {
        if (controlId == null) {
            return ControlPermission.denied();
        }
        Optional<Control> controlOpt = controlService.getControlById(controlId);
        if (controlOpt.isEmpty()) {
            return ControlPermission.denied();
        }
        return resolve(controlOpt.get(), user, null);
    }

    public ControlPermission resolve(Control control, User user) {
        return resolve(control, user, null);
    }

    public ControlPermission resolve(Control control, User user, ControlAssignmentDTO assignment) {
        if (control == null || user == null) {
            return ControlPermission.denied();
        }

        String userEmail = normalizeEmail(user.getMail());
        if (userEmail == null) {
            return ControlPermission.denied();
        }

        String normalizedRole = normalizeRole(user.getRole());
        boolean isAdmin = "ADMIN".equals(normalizedRole);
        boolean isSoqmRole = normalizedRole != null && normalizedRole.startsWith("SOQM");
        boolean isFacilitatorRole = "FACILITATOR".equals(normalizedRole);
        boolean isControlOperatorRole = "CONTROL_OPERATOR".equals(normalizedRole);
        boolean isProcessOwnerRole = "PROCESS_OWNER".equals(normalizedRole);
        boolean canEditAll = isAdmin || isSoqmRole;

        ControlAssignmentDTO resolvedAssignment = assignment != null
                ? assignment
                : controlAssignmentService.getAssignmentByControlId(control.getId());
        if (resolvedAssignment == null) {
            resolvedAssignment = new ControlAssignmentDTO();
        }

        boolean isFacilitator = containsEmail(resolvedAssignment.getFacilitator(), userEmail);
        boolean isControlOperator = containsEmail(resolvedAssignment.getControlOperator(), userEmail);
        boolean isAssignedSoqmLead = containsEmail(resolvedAssignment.getSoqmLead(), userEmail);
        boolean isProcessOwner = containsEmail(resolvedAssignment.getProcessOwner(), userEmail);
        boolean isSharedViewer = containsEmail(resolvedAssignment.getControlSharedWith(), userEmail);

        boolean isCreator = control.getCreatedBy() != null
                && normalizeEmail(control.getCreatedBy().getMail()) != null
                && normalizeEmail(control.getCreatedBy().getMail()).equals(userEmail);

        boolean canView = canEditAll
                || isCreator
                || isFacilitator
                || isControlOperator
                || isAssignedSoqmLead
                || isProcessOwner
                || isSharedViewer;

        String status = normalizeStatus(control.getPerformanceStatus());
        boolean isCompleted = "COMPLETED".equals(status);
        boolean sharedCompleted = isSharedViewer && isCompleted && !canEditAll;

        Set<String> allowedEditableFields = new LinkedHashSet<>();

        boolean canEditByStatus = (isCreator && "IN_PROGRESS".equals(status))
                || (isFacilitator && "IN_PROGRESS".equals(status))
                || (isControlOperator && "REVIEW".equals(status))
                || (isAssignedSoqmLead && "SOQM_HEAD_REVIEW".equals(status))
                || (isProcessOwner && "PROCESS_OWNER_REVIEW".equals(status));

        if (canEditByStatus) {
            if (isFacilitator || isControlOperator) {
                allowedEditableFields.add(ControlPermission.FIELD_CONTROL_STEPS_PERFORMED);
            }
            if (isProcessOwner) {
                allowedEditableFields.add(ControlPermission.FIELD_PROCESS_OWNER_COMMENTS);
            }
        }

        if (sharedCompleted) {
            if (isFacilitatorRole || isControlOperatorRole) {
                allowedEditableFields.add(ControlPermission.FIELD_CONTROL_STEPS_PERFORMED);
            } else if (isProcessOwnerRole) {
                allowedEditableFields.add(ControlPermission.FIELD_PROCESS_OWNER_COMMENTS);
            }
        }

        boolean canEdit = canView && (canEditAll || !allowedEditableFields.isEmpty());
        boolean canUseWorkflowActions = canView && !sharedCompleted;

        return new ControlPermission(
                canView,
                canEdit,
                allowedEditableFields,
                canUseWorkflowActions,
                canEditAll,
                isSharedViewer,
                sharedCompleted,
                isFacilitator,
                isControlOperator,
                isAssignedSoqmLead || isSoqmRole,
                isProcessOwner
        );
    }

    private boolean containsEmail(List<String> emails, String userEmail) {
        if (emails == null || userEmail == null) {
            return false;
        }
        for (String email : emails) {
            if (normalizeEmail(email) != null && normalizeEmail(email).equals(userEmail)) {
                return true;
            }
        }
        return false;
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String normalizeStatus(String status) {
        if (status == null || status.isBlank()) {
            return "DRAFT";
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }

    private String normalizeRole(String role) {
        if (role == null || role.isBlank()) {
            return null;
        }
        return role.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
    }
}
