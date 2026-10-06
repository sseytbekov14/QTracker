package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.ControlDetails;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlDetailsRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class WorkflowRequiredFieldService {
    private final ControlDetailsRepository controlDetailsRepository;
    private final ControlAssignmentRepository controlAssignmentRepository;

    public static final String MISSING_STEPS = "Required field is missing: Control steps performed and results";
    public static final String MISSING_OPERATOR_REVIEW = "Required field is missing: "
            + ControlStepsFields.OPERATOR_REVIEW_LABEL;

    /**
     * Control Steps Performed must be filled in to move a control on from In Progress, Review or SoQM review,
     * whoever does it (one person may hold several fields of a control). From Review (Submit to SoQM Team)
     * a control whose Facilitator and Control Operator are different people also needs Control Operator
     * Review and Results ({@link ControlStepsFields}); the other steps require nothing new.
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
        if ("REVIEW".equals(status) && stepsSplit(control)) {
            String review = details != null ? details.getControlOperatorReview() : null;
            if (review == null || review.trim().isEmpty()) {
                return Optional.of(MISSING_OPERATOR_REVIEW);
            }
        }

        return Optional.empty();
    }

    /** Whether the control has two steps fields, by its assignment as stored now. */
    public boolean stepsSplit(Control control) {
        if (control == null || control.getId() == null) {
            return false;
        }
        ControlAssignment assignment = controlAssignmentRepository.findByControlId(control.getId()).orElse(null);
        return assignment != null
                && ControlStepsFields.split(assignment.getFacilitator(), assignment.getControlOperator());
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
