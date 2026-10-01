package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlDetails;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.ControlDetailsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class WorkflowRequiredFieldService {
    private final ControlDetailsRepository controlDetailsRepository;

    public Optional<String> getMissingFieldMessage(Control control, User user) {
        if (control == null || user == null) {
            return Optional.empty();
        }
        String role = user.getRole();
        if (role == null || role.isBlank()) {
            return Optional.empty();
        }
        String status = normalizeStatus(control.getPerformanceStatus());
        ControlDetails details = controlDetailsRepository.findByControlId(control.getId()).orElse(null);

        boolean requiresSteps = (("FACILITATOR".equals(role) || "CONTROL_OPERATOR".equals(role)) && "IN_PROGRESS".equals(status))
                || (("CONTROL_OPERATOR".equals(role) || "FACILITATOR".equals(role)) && "REVIEW".equals(status))
                || ("SOQM_TEAM".equals(role) && "SOQM_HEAD_REVIEW".equals(status));

        if (!requiresSteps) {
            return Optional.empty();
        }

        String value = details != null ? details.getControlStepsPerformed() : null;
        if (value == null || value.trim().isEmpty()) {
            return Optional.of("Required field is missing: Control steps performed and results");
        }

        return Optional.empty();
    }

    /**
     * Every SoQM action (Send to Process Owner / Return to Operator) requires SoQM Head/Team Comments,
     * every Process Owner action (Complete / Return to SoQM Team) requires Process Owner Comments.
     * Checked against the stored value, based on the control's current status.
     */
    public Optional<String> getMissingReviewCommentMessage(Control control) {
        if (control == null) {
            return Optional.empty();
        }
        String status = normalizeStatus(control.getPerformanceStatus());
        if (!"SOQM_HEAD_REVIEW".equals(status) && !"PROCESS_OWNER_REVIEW".equals(status)) {
            return Optional.empty();
        }
        ControlDetails details = controlDetailsRepository.findByControlId(control.getId()).orElse(null);
        if ("SOQM_HEAD_REVIEW".equals(status)) {
            String value = details != null ? details.getSoqmHeadComments() : null;
            if (value == null || value.isBlank()) {
                return Optional.of("Required field is missing: SoQM Head/Team Comments");
            }
        } else {
            String value = details != null ? details.getProcessOwnerComments() : null;
            if (value == null || value.isBlank()) {
                return Optional.of("Required field is missing: Process Owner Comments");
            }
        }
        return Optional.empty();
    }

    private String normalizeStatus(String status) {
        if (status == null) {
            return "";
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }
}
