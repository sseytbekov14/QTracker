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

    @PostMapping("/submit-to-control-operator")
    @Transactional
    public ResponseEntity<?> submitToControlOperator(
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
            ResponseEntity<?> restrictedResponse = denyTransition(control, currentUser, WorkflowTransition.SUBMIT_TO_CONTROL_OPERATOR);
            if (restrictedResponse != null) {
                return restrictedResponse;
            }

            // Verify that current user is assigned as facilitator for this control
            Optional<ControlAssignment> assignmentOpt = controlAssignmentRepository.findByControlId(controlId);
            if (assignmentOpt.isEmpty()) {
                return ResponseEntity.status(400).body(Map.of("success", false, "message", "Control assignment not found"));
            }

            ControlAssignment assignment = assignmentOpt.get();

            Optional<String> missingField = requiredFieldService.getMissingFieldMessage(control);
            if (missingField.isPresent()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", missingField.get()));
            }

            // Verify Control Operator is assigned
            if (assignment.getControlOperator() == null || assignment.getControlOperator().isEmpty()) {
                return ResponseEntity.status(400).body(Map.of("success", false, "message", "Control Operator not assigned. Please assign a Control Operator first."));
            }

            String previousStatus = control.getPerformanceStatus();

            // Update workflow status to indicate it's under Control Operator review
            control.setPerformanceStatus("REVIEW");
            controlService.save(control);

            // Add workflow history record
            WorkflowHistory history = new WorkflowHistory();
            history.setControlId(controlId);
            history.setActionType(WorkflowActionType.SUBMIT_TO_OPERATOR);
            history.setPerformedByEmail(currentUser.getMail());
            history.setPerformedByName(currentUser.getDisplayName());
            history.setFromStep(previousStatus != null ? previousStatus : "IN_PROGRESS");
            history.setToStep("REVIEW");
            history.setComments("Control submitted to Control Operator for review");
            workflowHistoryRepository.save(history);

            // Notify Control Operator only
            sendNotificationToRole(control, assignment.getControlOperator(),
                    NotificationTemplateService.TemplateType.FACILITATOR_TO_OPERATOR,
                    false);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Control submitted to Control Operator");
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

    @PostMapping("/submit-to-soqm-lead")
    @Transactional
    public ResponseEntity<?> submitToSoqmLead(
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
            ResponseEntity<?> restrictedResponse = denyTransition(control, currentUser, WorkflowTransition.SUBMIT_TO_SOQM_TEAM);
            if (restrictedResponse != null) {
                return restrictedResponse;
            }

            // Verify that current user is assigned as control operator for this control
            Optional<ControlAssignment> assignmentOpt = controlAssignmentRepository.findByControlId(controlId);
            if (assignmentOpt.isEmpty()) {
                return ResponseEntity.status(400).body(Map.of("success", false, "message", "Control assignment not found"));
            }

            ControlAssignment assignment = assignmentOpt.get();

            Optional<String> missingField = requiredFieldService.getMissingFieldMessage(control);
            if (missingField.isPresent()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", missingField.get()));
            }

            // Verify SoQM Team is assigned
            if (assignment.getSoqmLead() == null || assignment.getSoqmLead().isEmpty()) {
                return ResponseEntity.status(400).body(Map.of("success", false, "message", "SoQM Team not assigned. Please assign a SoQM Team first."));
            }

            String previousStatus = control.getPerformanceStatus();

            // Update workflow status to indicate it's under SoQM Team review
            control.setPerformanceStatus("SOQM_HEAD_REVIEW");
            controlService.save(control);

            // Add workflow history record
            WorkflowHistory history = new WorkflowHistory();
            history.setControlId(controlId);
            history.setActionType(WorkflowActionType.SUBMIT_TO_SOQM_TEAM);
            history.setPerformedByEmail(currentUser.getMail());
            history.setPerformedByName(currentUser.getDisplayName());
            history.setFromStep(previousStatus != null ? previousStatus : "REVIEW");
            history.setToStep("SOQM_HEAD_REVIEW");
            history.setComments("Control submitted to SoQM Team for review");
            workflowHistoryRepository.save(history);

            // Notify SoQM Team/Delegate only
            boolean resubmitted = false;
            sendNotificationToRole(control, assignment.getSoqmLead(),
                    NotificationTemplateService.TemplateType.OPERATOR_TO_SOQM,
                    resubmitted);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Control submitted to SoQM Team");
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
            ResponseEntity<?> restrictedResponse = denyTransition(control, currentUser, WorkflowTransition.RETURN_TO_FACILITATOR);
            if (restrictedResponse != null) {
                return restrictedResponse;
            }

            // Verify that current user is assigned as control operator for this control
            Optional<ControlAssignment> assignmentOpt = controlAssignmentRepository.findByControlId(controlId);
            if (assignmentOpt.isEmpty()) {
                return ResponseEntity.status(400).body(Map.of("success", false, "message", "Control assignment not found"));
            }

            ControlAssignment assignment = assignmentOpt.get();

            // Every return needs a reason (spec 9.4)
            if (comments == null || comments.isBlank()) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "A comment is required to return the control"));
            }
            if (comments.length() > 2000) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Comment is too long. Maximum 2000 characters allowed."));
            }

            String previousStatus = control.getPerformanceStatus();
            
            // Update workflow status back to In Progress
            control.setPerformanceStatus("IN_PROGRESS");
            control.setReturnToFacilitatorComment(comments);
            controlService.save(control);

            // Add workflow history record
            WorkflowHistory history = new WorkflowHistory();
            history.setControlId(controlId);
            history.setActionType(WorkflowActionType.RETURN_TO_FACILITATOR);
            history.setPerformedByEmail(currentUser.getMail());
            history.setPerformedByName(currentUser.getDisplayName());
            history.setFromStep(previousStatus != null ? previousStatus : "REVIEW");
            history.setToStep("IN_PROGRESS");
            history.setComments(comments);
            workflowHistoryRepository.save(history);

            List<String> recipients = new ArrayList<>();
            if (assignment.getFacilitator() != null && !assignment.getFacilitator().isBlank()) {
                recipients.addAll(splitRecipients(assignment.getFacilitator()));
            }
            // The one who returns it is told as well when they are also a Facilitator of the control:
            // every move to a step a person holds is announced to them, as a submit is
            notificationService.sendReturnNotifications(
                    control,
                    recipients,
                    WorkflowTransition.Actor.CONTROL_OPERATOR.getDisplayName(),
                    currentUser.getDisplayName(),
                    "Facilitator",
                    comments,
                    "RETURN_TO_FACILITATOR"
            );

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Control returned to Facilitator");
            response.put("controlStatus", control.getPerformanceStatus());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            rollbackCurrentTransaction();
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "message", "Error returning control: " + e.getMessage()
            ));
        }
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
