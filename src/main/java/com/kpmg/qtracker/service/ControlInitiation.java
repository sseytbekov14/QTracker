package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Initiate: a draft goes In Progress. The Initiate page and Create Control (the next cycle starts In Progress, business
 * decision of 2026-10-14) both do it here: the required fields and SoQM Year checked, the status set, the workflow
 * steps made, the "Initiated" history entry by the person who pressed, and the Control Initiated notification to the
 * Facilitators, by e-mail too when e-mail is on.
 */
@Service
@RequiredArgsConstructor
public class ControlInitiation {

    private final ControlService controlService;
    private final WorkflowService workflowService;

    /**
     * What the control still misses to be initiated (InitiationReadiness.missing), with the SoQM Year given or, when
     * none is given, the stored one; empty when it can be initiated.
     */
    public static List<String> missing(Control control, ControlAssignmentDTO assignment, String soqmYear) {
        return InitiationReadiness.missing(control, assignment, soqmYear != null ? soqmYear : control.getSoqmYear());
    }

    /**
     * Initiates a control that misses nothing ({@link #missing}); the SoQM Year given replaces the stored one (null
     * keeps it). Runs in the caller's transaction, so a failure leaves no half-done start.
     */
    @Transactional
    public void start(Control control, ControlAssignmentDTO assignment, String soqmYear, User initiatedBy) {
        List<String> missing = missing(control, assignment, soqmYear);
        if (!missing.isEmpty()) {
            throw new IllegalStateException("Required fields are missing: " + String.join(", ", missing));
        }
        if (soqmYear != null) {
            control.setSoqmYear(soqmYear);
        }
        String facilitatorEmail = assignment.getFacilitator().stream()
                .filter(email -> email != null && !email.isBlank())
                .findFirst()
                .orElseThrow();

        control.setPerformanceStatus("IN_PROGRESS");
        controlService.save(control);

        workflowService.initiateWorkflow(control.getId(), facilitatorEmail, initiatedBy);
    }
}
