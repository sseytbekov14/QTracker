package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Optional;

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
        AccessPolicy.ControlFacts facts = facts(control, user, assignment);
        if (facts == null) {
            return ControlPermission.denied();
        }
        return AccessPolicy.resolve(AccessPolicy.Subject.of(user), facts);
    }

    /** How the user stands on the control (the input of {@link AccessPolicy}); null without a control or user. */
    public AccessPolicy.ControlFacts facts(Control control, User user, ControlAssignmentDTO assignment) {
        if (control == null || user == null) {
            return null;
        }
        String userEmail = normalizeEmail(user.getMail());
        if (userEmail == null) {
            return null;
        }
        ControlAssignmentDTO resolvedAssignment = assignment != null
                ? assignment
                : controlAssignmentService.getAssignmentByControlId(control.getId());
        if (resolvedAssignment == null) {
            resolvedAssignment = new ControlAssignmentDTO();
        }
        return new AccessPolicy.ControlFacts(
                control.getPerformanceStatus(),
                AccessPolicy.isKdnControl(control.getControlId()),
                containsEmail(resolvedAssignment.getFacilitator(), userEmail),
                containsEmail(resolvedAssignment.getControlOperator(), userEmail),
                containsEmail(resolvedAssignment.getSoqmLead(), userEmail),
                containsEmail(resolvedAssignment.getProcessOwner(), userEmail),
                containsEmail(resolvedAssignment.getControlSharedWith(), userEmail),
                isCreator(control, user));
    }

    /** The user created the control (Control.createdBy, by id, else by e-mail ignoring case). */
    public static boolean isCreator(Control control, User user) {
        User creator = control != null ? control.getCreatedBy() : null;
        if (creator == null || user == null) {
            return false;
        }
        if (creator.getId() != null && creator.getId().equals(user.getId())) {
            return true;
        }
        return creator.getMail() != null && user.getMail() != null
                && creator.getMail().trim().equalsIgnoreCase(user.getMail().trim());
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
}
