package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.entity.WorkflowHistory;
import com.kpmg.qtracker.enums.WorkflowActionType;
import com.kpmg.qtracker.service.ControlPermissionService;
import com.kpmg.qtracker.service.IControlService;
import com.kpmg.qtracker.service.NotificationService;
import com.kpmg.qtracker.service.NotificationTemplateService;
import com.kpmg.qtracker.service.WorkflowRequiredFieldService;
import com.kpmg.qtracker.service.WorkflowMove;
import com.kpmg.qtracker.service.WorkflowMoveService;
import com.kpmg.qtracker.service.WorkflowTransition;
import com.kpmg.qtracker.service.WorkflowTransitionGuard;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.WorkflowHistoryRepository;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;

import java.util.*;
import com.kpmg.qtracker.util.EmailList;

@RestController
@RequestMapping("/api/workflow")
@RequiredArgsConstructor
public class WorkflowTransitionController {
    private final IControlService controlService;
    private final ControlAssignmentRepository controlAssignmentRepository;
    private final WorkflowHistoryRepository workflowHistoryRepository;
    private final NotificationService notificationService;
    private final WorkflowRequiredFieldService requiredFieldService;
    private final ControlPermissionService controlPermissionService;
    private final WorkflowTransitionGuard transitionGuard;
    private final WorkflowMoveService workflowMoveService;

    @PostMapping("/submit-to-control-operator")
    @Transactional
    public ResponseEntity<?> submitToControlOperator(
            @RequestParam Long controlId,
            @RequestParam(required = false) String comments,
            HttpSession session) {
        return perform(controlId, session, List.of(WorkflowTransition.SUBMIT_TO_CONTROL_OPERATOR), comments,
                "Control submitted to Control Operator", "Error submitting control: ");
    }

    @PostMapping("/submit-to-soqm-lead")
    @Transactional
    public ResponseEntity<?> submitToSoqmLead(
            @RequestParam Long controlId,
            @RequestParam(required = false) String comments,
            HttpSession session) {
        return perform(controlId, session, List.of(WorkflowTransition.SUBMIT_TO_SOQM_TEAM), comments,
                "Control submitted to SoQM Team", "Error submitting control: ");
    }

    /**
     * Moves the control to a status: the user's own step, or as SoQM the step of any role, on to the next
     * status or back to any earlier one (business decision 3, {@link com.kpmg.qtracker.service.AccessPolicy#move}).
     */
    @PostMapping("/move")
    @Transactional
    public ResponseEntity<?> move(@RequestParam Long controlId,
                                  @RequestParam String targetStatus,
                                  @RequestParam(required = false) String comments,
                                  HttpSession session) {
        try {
            User currentUser = (User) session.getAttribute("currentUser");
            if (currentUser == null) {
                return ResponseEntity.status(401).body(Map.of("success", false, "message", "Unauthorized"));
            }
            Optional<Control> controlOpt = controlService.getControlById(controlId);
            if (controlOpt.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("success", false, "message", "Control not found"));
            }
            WorkflowMoveService.Outcome outcome =
                    workflowMoveService.moveTo(controlOpt.get(), currentUser, targetStatus, comments);
            return respond(outcome, outcome.ok()
                    ? "Control moved to " + WorkflowMove.displayStatus(outcome.newStatus()) : null);
        } catch (Exception e) {
            rollbackCurrentTransaction();
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "message", "Error moving control: " + e.getMessage()
            ));
        }
    }

    @PostMapping("/shared-submit-to-soqm-lead")
    @Transactional
    public ResponseEntity<?> sharedSubmitToSoqmLead(
            @RequestParam Long controlId,
            HttpSession session) {

        try {
            User currentUser = (User) session.getAttribute("currentUser");
            if (currentUser == null) {
                return ResponseEntity.status(401).body(Map.of("success", false, "message", "Unauthorized"));
            }

            Optional<Control> controlOpt = controlService.getControlById(controlId);
            if (controlOpt.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("success", false, "message", "Control not found"));
            }

            Control control = controlOpt.get();
            ResponseEntity<?> restrictedResponse = denyTransition(control, currentUser, WorkflowTransition.SHARED_RESUBMIT_TO_SOQM_TEAM);
            if (restrictedResponse != null) {
                return restrictedResponse;
            }

            Optional<ControlAssignment> assignmentOpt = controlAssignmentRepository.findByControlId(controlId);
            if (assignmentOpt.isEmpty()) {
                return ResponseEntity.status(400).body(Map.of("success", false, "message", "Control assignment not found"));
            }

            ControlAssignment assignment = assignmentOpt.get();
            String userEmail = currentUser.getMail();

            // Verify SoQM Team is assigned
            if (assignment.getSoqmLead() == null || assignment.getSoqmLead().isEmpty()) {
                return ResponseEntity.status(400).body(Map.of("success", false,
                        "message", "SoQM Team not assigned"));
            }

            String previousStatus = control.getPerformanceStatus();

            // Transition: COMPLETED → SOQM_HEAD_REVIEW
            control.setPerformanceStatus("SOQM_HEAD_REVIEW");
            controlService.save(control);

            // Workflow history
            WorkflowHistory history = new WorkflowHistory();
            history.setControlId(controlId);
            history.setActionType(WorkflowActionType.SUBMIT_TO_SOQM_TEAM);
            history.setPerformedByEmail(userEmail);
            history.setPerformedByName(currentUser.getDisplayName());
            history.setFromStep(previousStatus);
            history.setToStep("SOQM_HEAD_REVIEW");
            history.setComments("Shared viewer submitted completed control to SoQM Team for review");
            workflowHistoryRepository.save(history);

            // Notify SoQM Team
            sendNotificationToRole(control, assignment.getSoqmLead(),
                    NotificationTemplateService.TemplateType.OPERATOR_TO_SOQM, false);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Control submitted to SoQM Team for review");
            response.put("controlStatus", control.getPerformanceStatus());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            rollbackCurrentTransaction();
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "message", "Error submitting control: " + e.getMessage()
            ));
        }
    }

    private void sendNotificationToRole(Control control,
                                        String assignedField,
                                        NotificationTemplateService.TemplateType templateType,
                                        boolean resubmitted) {
        if (control == null || assignedField == null || assignedField.isBlank()) {
            return;
        }
        List<String> recipients = splitRecipients(assignedField);
        if (!recipients.isEmpty()) {
            notificationService.sendTemplateNotifications(control, recipients, templateType, resubmitted);
        }
    }

    private List<String> splitRecipients(String raw) {
        return new ArrayList<>(EmailList.parse(raw));
    }

    private String removeEmailFromList(String commaSeparated, String emailToRemove) {
        if (commaSeparated == null || emailToRemove == null) {
            return commaSeparated;
        }
        List<String> emails = splitRecipients(commaSeparated);
        emails.removeIf(e -> e.equalsIgnoreCase(emailToRemove));
        return emails.isEmpty() ? null : String.join(",", emails);
    }

    @PostMapping("/return-to-facilitator")
    @Transactional
    public ResponseEntity<?> returnToFacilitator(
            @RequestParam Long controlId,
            @RequestParam(required = false) String comments,
            HttpSession session) {
        return perform(controlId, session, List.of(WorkflowTransition.RETURN_TO_FACILITATOR), comments,
                "Control returned to Facilitator", "Error returning control: ");
    }

    /** One standard step through {@link WorkflowMoveService}, answered as these endpoints always answered. */
    private ResponseEntity<?> perform(Long controlId, HttpSession session, List<WorkflowTransition> candidates,
                                      String comments, String successMessage, String errorPrefix) {
        try {
            User currentUser = (User) session.getAttribute("currentUser");
            if (currentUser == null) {
                return ResponseEntity.status(401).body(Map.of("success", false, "message", "Unauthorized"));
            }
            Optional<Control> controlOpt = controlService.getControlById(controlId);
            if (controlOpt.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("success", false, "message", "Control not found"));
            }
            return respond(workflowMoveService.perform(controlOpt.get(), currentUser, candidates, comments),
                    successMessage);
        } catch (Exception e) {
            rollbackCurrentTransaction();
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "message", errorPrefix + e.getMessage()
            ));
        }
    }

    private ResponseEntity<?> respond(WorkflowMoveService.Outcome outcome, String successMessage) {
        if (!outcome.ok()) {
            return ResponseEntity.status(outcome.httpStatus())
                    .body(Map.of("success", false, "message", outcome.message()));
        }
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", successMessage);
        response.put("controlStatus", outcome.newStatus());
        response.put("onBehalf", outcome.move().onBehalf());
        return ResponseEntity.ok(response);
    }

    private ResponseEntity<?> denyTransition(Control control, User currentUser, WorkflowTransition transition) {
        WorkflowTransitionGuard.Decision decision = transitionGuard.check(
                control, controlPermissionService.resolve(control, currentUser), transition);
        if (decision.allowed()) {
            return null;
        }
        return ResponseEntity.status(decision.httpStatus())
                .body(Map.of("success", false, "message", decision.message()));
    }

    /**
     * Errors are turned into responses instead of propagating, so the transaction would commit a
     * half-done transition (status without history or workflow steps); mark it for rollback instead.
     */
    private void rollbackCurrentTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
    }
}
