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

    private String normalizeStatus(String status) {
        if (status == null) {
            return "";
        }
        return status.trim().toUpperCase(Locale.ROOT);
    }
}
