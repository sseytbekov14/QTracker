package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlDetails;
import com.kpmg.qtracker.repository.ControlDetailsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class WorkflowRequiredFieldService {
    private final ControlDetailsRepository controlDetailsRepository;

    public static final String MISSING_STEPS = "Required field is missing: Control steps performed and results";

    /**
     * The Details field of a step, for the "Your step" hint on View Control. {@code check}: the field was filled
     * at an earlier step and is only reviewed here; {@code required}: the step's actions are refused while it is
     * empty (the checks below), else the hint names it as optional.
     */
    public record StepField(String field, String label, boolean check, boolean required, String actions) {
    }

    /**
     * The field of the step a control in this status is at. Review is the Control Operator's step, whose field is
     * Control Operator's Program, required by no step. Draft and Completed have none.
     */
    public static Optional<StepField> stepField(String performanceStatus) {
        String status = performanceStatus == null ? "" : performanceStatus.trim().toUpperCase(Locale.ROOT);
        return switch (status) {
            case "IN_PROGRESS" -> Optional.of(new StepField(ControlPermission.FIELD_CONTROL_STEPS_PERFORMED,
                    ControlStepsFields.STEPS_LABEL, false, true, "Submit for Review"));
            case "REVIEW" -> Optional.of(new StepField(ControlPermission.FIELD_CONTROL_OPERATOR_REVIEW,
                    ControlStepsFields.OPERATOR_PROGRAM_LABEL, false, false,
                    "Submit for SoQM Team Review or Return to Facilitator"));
            case "SOQM_HEAD_REVIEW" -> Optional.of(new StepField("soqmHeadComments",
                    "SoQM Head/Team Comments", false, true, "Send to Process Owner or Return to Operator"));
            case "PROCESS_OWNER_REVIEW" -> Optional.of(new StepField(ControlPermission.FIELD_PROCESS_OWNER_COMMENTS,
                    "Process Owner Comments", false, true, "Complete or Return to Control Operator"));
            default -> Optional.empty();
        };
    }

    /**
     * Control Steps Performed must be filled in to move a control on from In Progress, Review or SoQM review,
     * whoever does it (one person may hold several fields of a control). Control Operator's Program is required
     * by no step, whoever the Facilitator and the Control Operator are.
     */
    public Optional<String> getMissingFieldMessage(Control control) {
        if (control == null) {
            return Optional.empty();
        }
        String status = normalizeStatus(control.getPerformanceStatus());
        if (!"IN_PROGRESS".equals(status) && !"REVIEW".equals(status) && !"SOQM_HEAD_REVIEW".equals(status)) {
            return Optional.empty();
        }
        ControlDetails details = controlDetailsRepository.findByControlId(control.getId()).orElse(null);

        String value = details != null ? details.getControlStepsPerformed() : null;
        if (value == null || value.trim().isEmpty()) {
            return Optional.of(MISSING_STEPS);
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
