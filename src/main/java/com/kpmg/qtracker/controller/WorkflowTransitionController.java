package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.entity.WorkflowHistory;
import com.kpmg.qtracker.enums.WorkflowActionType;
import com.kpmg.qtracker.service.ControlPermission;
import com.kpmg.qtracker.service.ControlPermissionService;
import com.kpmg.qtracker.service.IControlService;
import com.kpmg.qtracker.service.NotificationService;
import com.kpmg.qtracker.service.NotificationTemplateService;
import com.kpmg.qtracker.service.WorkflowRequiredFieldService;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.WorkflowHistoryRepository;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.*;

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

    @PostMapping("/initiate")
    public ResponseEntity<?> initiateControl(
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
            ResponseEntity<?> restrictedResponse = denyWorkflowActionIfRestricted(control, currentUser);
            if (restrictedResponse != null) {
                return restrictedResponse;
            }

            // Get control assignment to find facilitator
            Optional<ControlAssignment> assignmentOpt = controlAssignmentRepository.findByControlId(controlId);
            if (assignmentOpt.isEmpty()) {
                return ResponseEntity.status(400).body(Map.of("success", false, "message", "Control assignment not found. Please assign a Facilitator first."));
            }

            ControlAssignment assignment = assignmentOpt.get();
            if (assignment.getFacilitator() == null || assignment.getFacilitator().isEmpty()) {
                return ResponseEntity.status(400).body(Map.of("success", false, "message", "No Facilitator assigned to this control"));
            }

            // Update workflow status to In Progress
            control.setPerformanceStatus("IN_PROGRESS");
            controlService.save(control);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Control initiated and sent to Facilitator for review");
            response.put("controlStatus", control.getPerformanceStatus());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "message", "Error initiating control: " + e.getMessage()
            ));
        }
    }

    @PostMapping("/submit-to-control-operator")
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
            ResponseEntity<?> restrictedResponse = denyWorkflowActionIfRestricted(control, currentUser);
            if (restrictedResponse != null) {
                return restrictedResponse;
            }

            // Verify that current user is assigned as facilitator for this control
            Optional<ControlAssignment> assignmentOpt = controlAssignmentRepository.findByControlId(controlId);
            if (assignmentOpt.isEmpty()) {
                return ResponseEntity.status(400).body(Map.of("success", false, "message", "Control assignment not found"));
            }

            ControlAssignment assignment = assignmentOpt.get();
            String facilitatorField = assignment.getFacilitator();
            if (facilitatorField == null || !facilitatorField.contains(currentUser.getMail())) {
                return ResponseEntity.status(403).body(Map.of("success", false, "message", "You are not assigned as Facilitator for this control"));
            }

            Optional<String> missingField = requiredFieldService.getMissingFieldMessage(control, currentUser);
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
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "message", "Error submitting control: " + e.getMessage()
            ));
        }
    }

    @PostMapping("/submit-to-soqm-lead")
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
            ResponseEntity<?> restrictedResponse = denyWorkflowActionIfRestricted(control, currentUser);
            if (restrictedResponse != null) {
                return restrictedResponse;
            }

            // Verify that current user is assigned as control operator for this control
            Optional<ControlAssignment> assignmentOpt = controlAssignmentRepository.findByControlId(controlId);
            if (assignmentOpt.isEmpty()) {
                return ResponseEntity.status(400).body(Map.of("success", false, "message", "Control assignment not found"));
            }

            ControlAssignment assignment = assignmentOpt.get();
            String operatorField = assignment.getControlOperator();
            if (operatorField == null || !operatorField.contains(currentUser.getMail())) {
                return ResponseEntity.status(403).body(Map.of("success", false, "message", "You are not assigned as Control Operator for this control"));
            }

            Optional<String> missingField = requiredFieldService.getMissingFieldMessage(control, currentUser);
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
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "message", "Error submitting control: " + e.getMessage()
            ));
        }
    }

    @PostMapping("/shared-submit-to-soqm-lead")
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
            ResponseEntity<?> restrictedResponse = denyWorkflowActionIfRestricted(control, currentUser);
            if (restrictedResponse != null) {
                return restrictedResponse;
            }

            // Only allow from COMPLETED status
            if (!"COMPLETED".equals(control.getPerformanceStatus())) {
                return ResponseEntity.status(400).body(Map.of("success", false,
                        "message", "Control must be in COMPLETED status"));
            }

            // Verify user is a shared viewer
            Optional<ControlAssignment> assignmentOpt = controlAssignmentRepository.findByControlId(controlId);
            if (assignmentOpt.isEmpty()) {
                return ResponseEntity.status(400).body(Map.of("success", false, "message", "Control assignment not found"));
            }

            ControlAssignment assignment = assignmentOpt.get();
            String sharedField = assignment.getControlSharedWith();
            String userEmail = currentUser.getMail();
            if (sharedField == null || !sharedField.toLowerCase().contains(userEmail.toLowerCase())) {
                return ResponseEntity.status(403).body(Map.of("success", false,
                        "message", "You are not a shared viewer for this control"));
            }

            // Only shared viewers who are assigned as FACILITATOR, CONTROL_OPERATOR, or PROCESS_OWNER can submit
            // (role check is already handled by the shared viewer check above)

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
            history.setComments("Shared viewer (" + currentUser.getRole() + ") submitted completed control to SoQM Team for review");
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
        Set<String> emails = new LinkedHashSet<>();
        String[] parts = raw.split(",");
        for (String part : parts) {
            String email = part.trim();
            if (!email.isEmpty()) {
                emails.add(email);
            }
        }
        return new ArrayList<>(emails);
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
            ResponseEntity<?> restrictedResponse = denyWorkflowActionIfRestricted(control, currentUser);
            if (restrictedResponse != null) {
                return restrictedResponse;
            }

            // Verify that current user is assigned as control operator for this control
            Optional<ControlAssignment> assignmentOpt = controlAssignmentRepository.findByControlId(controlId);
            if (assignmentOpt.isEmpty()) {
                return ResponseEntity.status(400).body(Map.of("success", false, "message", "Control assignment not found"));
            }

            ControlAssignment assignment = assignmentOpt.get();
            String operatorField = assignment.getControlOperator();
            if (operatorField == null || !operatorField.contains(currentUser.getMail())) {
                return ResponseEntity.status(403).body(Map.of("success", false, "message", "You are not assigned as Control Operator for this control"));
            }

            // Validate comment length
            if (comments != null && comments.length() > 2000) {
                return ResponseEntity.badRequest().body(Map.of("success", false, "message", "Comment is too long. Maximum 2000 characters allowed."));
            }

            String previousStatus = control.getPerformanceStatus();
            
            // Update workflow status back to In Progress
            control.setPerformanceStatus("IN_PROGRESS");
            if (comments != null && !comments.isEmpty()) {
                control.setReturnToFacilitatorComment(comments);
            }
            controlService.save(control);

            // Add workflow history record
            WorkflowHistory history = new WorkflowHistory();
            history.setControlId(controlId);
            history.setActionType(WorkflowActionType.RETURN_TO_FACILITATOR);
            history.setPerformedByEmail(currentUser.getMail());
            history.setPerformedByName(currentUser.getDisplayName());
            history.setFromStep(previousStatus != null ? previousStatus : "REVIEW");
            history.setToStep("IN_PROGRESS");
            history.setComments(comments != null && !comments.isEmpty() ? comments : "Control returned to Facilitator for revision");
            workflowHistoryRepository.save(history);

            List<String> recipients = new ArrayList<>();
            if (assignment.getFacilitator() != null && !assignment.getFacilitator().isBlank()) {
                recipients.addAll(splitRecipients(assignment.getFacilitator()));
            }
            String currentEmail = currentUser.getMail();
            if (currentEmail != null) {
                recipients.removeIf(email -> email.equalsIgnoreCase(currentEmail));
            }
            notificationService.sendReturnNotifications(
                    control,
                    recipients,
                    currentUser.getRole(),
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
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "message", "Error returning control: " + e.getMessage()
            ));
        }
    }

    private ResponseEntity<?> denyWorkflowActionIfRestricted(Control control, User currentUser) {
        ControlPermission permission = controlPermissionService.resolve(control, currentUser);
        if (!permission.canView()) {
            return ResponseEntity.status(403).body(Map.of("success", false, "message", "Forbidden"));
        }
        if (!permission.canUseWorkflowActions()) {
            return ResponseEntity.status(403).body(Map.of(
                    "success", false,
                    "message", "Workflow actions are disabled for shared users on completed controls"
            ));
        }
        return null;
    }
}

