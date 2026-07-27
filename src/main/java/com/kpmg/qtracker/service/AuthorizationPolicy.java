package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.dto.ControlDetailsDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.security.UserPrincipal;
import com.kpmg.qtracker.security.UserPrincipalService;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;

import java.security.Principal;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;

@Service
@RequiredArgsConstructor
public class AuthorizationPolicy {

    private final ControlPermissionService controlPermissionService;
    private final IControlService controlService;
    private final UserService userService;
    private final UserPrincipalService userPrincipalService;
    private final ControlAssignmentService controlAssignmentService;
    private final ControlDetailsService controlDetailsService;

    private static final Set<String> PROCESS_OWNER_WORKFLOW_TRANSITIONS = Set.of(
            "COMPLETE",
            "COMPLETE_CONTROL",
            "RETURN_TO_SOQM_TEAM",
            "SEND_FOR_REVISION",
            "REJECT"
    );

    public void checkCanReadControl(Long controlId, Principal principal) {
        UserPrincipal userPrincipal = requirePrincipal(principal);
        User user = requireUser(principal);
        ControlPermission permission = controlPermissionService.resolve(controlId, user);

        boolean allowed;
        if (hasRole(userPrincipal, "SOQM_TEAM")) {
            allowed = true;
        } else if (hasRole(userPrincipal, "PROCESS_OWNER")) {
            allowed = isOwnerOfControl(controlId, userPrincipal);
        } else if (hasRole(userPrincipal, "FACILITATOR")) {
            allowed = isAssignedToControl(controlId, userPrincipal);
        } else if (hasRole(userPrincipal, "CONTROL_OPERATOR")) {
            allowed = isAssignedToControl(controlId, userPrincipal);
        } else {
            allowed = permission.canView();
        }

        if (!(allowed && permission.canView())) {
            throw new AccessDeniedException("Access denied");
        }
    }

    public void checkCanModifyControl(Long controlId, Principal principal) {
        User user = requireUser(principal);
        ControlPermission permission = controlPermissionService.resolve(controlId, user);

        if (!permission.canEdit()) {
            throw new AccessDeniedException("Access denied");
        }
    }

    public void checkAttachmentAccess(String filename, Principal principal) {
        UserPrincipal userPrincipal = requirePrincipal(principal);
        User user = requireUser(principal);
        if (filename == null || filename.isBlank()) {
            throw new AccessDeniedException("Access denied");
        }
        String normalized = filename.trim();
        Optional<Control> relatedControl = controlService.getAllControls().stream()
                .filter(control -> hasAttachment(control.getAttachmentDetailsPath(), normalized)
                        || hasAttachment(control.getAttachmentDocumentsPath(), normalized))
                .findFirst();
        if (relatedControl.isEmpty()) {
            throw new AccessDeniedException("Access denied");
        }

        Control control = relatedControl.get();
        ControlPermission permission = controlPermissionService.resolve(control, user);
        boolean allowed;
        if (hasRole(userPrincipal, "SOQM_TEAM")) {
            allowed = true;
        } else if (hasRole(userPrincipal, "PROCESS_OWNER")) {
            allowed = isOwnerOfControl(control.getId(), userPrincipal);
        } else {
            allowed = isAssignedToControl(control.getId(), userPrincipal);
        }

        if (!(allowed && permission.canView())) {
            throw new AccessDeniedException("Access denied");
        }
    }

    public void checkWorkflowPermission(Long controlId, String transition, Principal principal) {
        UserPrincipal userPrincipal = requirePrincipal(principal);
        User user = requireUser(principal);
        ControlPermission permission = controlPermissionService.resolve(controlId, user);

        boolean allowed;
        if (hasRole(userPrincipal, "SOQM_TEAM")) {
            allowed = true;
        } else if (hasRole(userPrincipal, "PROCESS_OWNER")) {
            String normalizedTransition = normalizeTransition(transition);
            allowed = isOwnerOfControl(controlId, userPrincipal)
                    && PROCESS_OWNER_WORKFLOW_TRANSITIONS.contains(normalizedTransition);
        } else if (hasRole(userPrincipal, "CONTROL_OPERATOR")) {
            allowed = canEditAssignedFields(controlId, userPrincipal);
        } else if (hasRole(userPrincipal, "FACILITATOR")) {
            allowed = isAssignedToControl(controlId, userPrincipal);
        } else {
            allowed = permission.canUseWorkflowActions();
        }

        if (!(allowed && permission.canUseWorkflowActions())) {
            throw new AccessDeniedException("Access denied");
        }
    }

    public void checkUserAccess(Long userId, Principal principal) {
        User user = requireUser(principal);
        if (userId == null || user.getId() == null || !userId.equals(user.getId())) {
            throw new AccessDeniedException("Access denied");
        }
    }

    public boolean hasRole(UserPrincipal principal, String role) {
        if (principal == null || role == null || role.isBlank()) {
            return false;
        }
        String normalizedRole = role.trim().toUpperCase(Locale.ROOT);
        return principal.getRoles().stream()
                .map(value -> value == null ? "" : value.trim().toUpperCase(Locale.ROOT))
                .anyMatch(value -> value.equals(normalizedRole)
                        || value.equals("ROLE_" + normalizedRole));
    }

    public boolean isOwnerOfControl(Long controlId, UserPrincipal principal) {
        if (controlId == null || principal == null || principal.getEmail() == null) {
            return false;
        }
        ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(controlId);
        if (containsEmail(assignment.getProcessOwner(), principal.getEmail())) {
            return true;
        }

        return controlService.getControlById(controlId)
                .map(Control::getCreatedBy)
                .map(User::getMail)
                .map(email -> email.equalsIgnoreCase(principal.getEmail()))
                .orElse(false);
    }

    public boolean isAssignedToControl(Long controlId, UserPrincipal principal) {
        if (controlId == null || principal == null || principal.getEmail() == null) {
            return false;
        }
        ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(controlId);
        String email = principal.getEmail();
        return containsEmail(assignment.getFacilitator(), email)
                || containsEmail(assignment.getControlOperator(), email)
                || containsEmail(assignment.getProcessOwner(), email)
                || containsEmail(assignment.getSoqmLead(), email)
                || containsEmail(assignment.getControlSharedWith(), email);
    }

    public boolean canEditAssignedFields(Long controlId, UserPrincipal principal) {
        if (!hasRole(principal, "CONTROL_OPERATOR") || !isAssignedToControl(controlId, principal)) {
            return false;
        }
        User user = userService.getUserByEmail(principal.getEmail()).orElse(null);
        if (user == null) {
            return false;
        }
        ControlPermission permission = controlPermissionService.resolve(controlId, user);
        return permission.canEdit()
                && (permission.canEditStepsPerformed() || permission.canEditProcessOwnerComments());
    }

    public ControlDetailsDTO filterReadableFields(ControlDetailsDTO dto, UserPrincipal principal) {
        if (dto == null || principal == null) {
            return dto;
        }

        if (hasRole(principal, "SOQM_TEAM") || hasRole(principal, "PROCESS_OWNER")) {
            return dto;
        }

        ControlDetailsDTO filtered = new ControlDetailsDTO();
        filtered.setControlId(dto.getControlId());
        filtered.setProcessName(dto.getProcessName());
        filtered.setHomogeneity(dto.getHomogeneity());
        filtered.setReferencesToControl(dto.getReferencesToControl());
        filtered.setDepartment(dto.getDepartment());
        filtered.setProcessActivities(dto.getProcessActivities());
        filtered.setOtherRelatedControls(dto.getOtherRelatedControls());
        filtered.setItApplications(dto.getItApplications());
        filtered.setControlStepsPerformed(dto.getControlStepsPerformed());

        if (hasRole(principal, "CONTROL_OPERATOR")) {
            filtered.setProcessOwnerComments(dto.getProcessOwnerComments());
        }

        // FACILITATOR and CONTROL_OPERATOR must not read SoQM comments.
        filtered.setSoqmHeadComments(null);
        return filtered;
    }

    public void validateEditableFields(ControlDetailsDTO dto, UserPrincipal principal) {
        if (dto == null || principal == null) {
            return;
        }

        if (hasRole(principal, "SOQM_TEAM") || hasRole(principal, "PROCESS_OWNER")) {
            return;
        }

        ControlDetailsDTO existing = null;
        if (dto.getControlId() != null) {
            existing = controlDetailsService.getDetailsByControlId(dto.getControlId());
        }

        if (hasRole(principal, "FACILITATOR") || hasRole(principal, "CONTROL_OPERATOR")) {
            if (hasChanged(existing == null ? null : existing.getSoqmHeadComments(), dto.getSoqmHeadComments())) {
                throw new AccessDeniedException("Access denied");
            }
        }

        if (hasRole(principal, "FACILITATOR")) {
            if (hasChanged(existing == null ? null : existing.getProcessOwnerComments(), dto.getProcessOwnerComments())) {
                throw new AccessDeniedException("Access denied");
            }
        }
    }

    private boolean hasChanged(String oldValue, String newValue) {
        String oldNorm = oldValue == null ? "" : oldValue.trim();
        String newNorm = newValue == null ? "" : newValue.trim();
        return !oldNorm.equals(newNorm);
    }

    private User requireUser(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new AccessDeniedException("Access denied");
        }
        return userService.getUserByEmail(principal.getName())
                .orElseThrow(() -> new AccessDeniedException("Access denied"));
    }

    private UserPrincipal requirePrincipal(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new AccessDeniedException("Access denied");
        }
        if (principal instanceof UserPrincipal userPrincipal) {
            return userPrincipal;
        }
        return userPrincipalService.loadByEmail(principal.getName())
                .orElseThrow(() -> new AccessDeniedException("Access denied"));
    }

    private String normalizeTransition(String transition) {
        if (transition == null || transition.isBlank()) {
            return "";
        }
        return transition.trim()
                .replace('-', '_')
                .replace(' ', '_')
                .toUpperCase(Locale.ROOT);
    }

    private boolean containsEmail(List<String> emails, String targetEmail) {
        if (emails == null || emails.isEmpty() || targetEmail == null) {
            return false;
        }
        return emails.stream()
                .filter(value -> value != null && !value.isBlank())
                .anyMatch(value -> value.trim().equalsIgnoreCase(targetEmail));
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private boolean hasAttachment(String storedPath, String filename) {
        if (storedPath == null || storedPath.isBlank()) {
            return false;
        }
        String[] parts = storedPath.split(";");
        for (String part : parts) {
            if (part != null && part.trim().equalsIgnoreCase(filename)) {
                return true;
            }
        }
        return false;
    }
}
