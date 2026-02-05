package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.entity.WorkflowHistory;
import com.kpmg.qtracker.enums.WorkflowActionType;
import com.kpmg.qtracker.service.IControlService;
import com.kpmg.qtracker.service.NotificationService;
import com.kpmg.qtracker.service.NotificationTemplateService;
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

            // Get control assignment to find facilitator
            Optional<ControlAssignment> assignmentOpt = controlAssignmentRepository.findByControlId(controlId);
            if (assignmentOpt.isEmpty()) {
                return ResponseEntity.status(400).body(Map.of("success", false, "message", "Control assignment not found. Please assign a Facilitator first."));
            }

            ControlAssignment assignment = assignmentOpt.get();
            if (assignment.getFacilitator() == null || assignment.getFacilitator().isEmpty()) {
                return ResponseEntity.status(400).body(Map.of("success", false, "message", "No Facilitator assigned to this control"));
            }

            // Update control status to Facilitator Review
            control.setControlStatus("Facilitator Review");
            controlService.save(control);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Control initiated and sent to Facilitator for review");
            response.put("controlStatus", control.getControlStatus());

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

            // Only Facilitator can submit
            if (!"FACILITATOR".equals(currentUser.getRole())) {
                return ResponseEntity.status(403).body(Map.of("success", false, "message", "Only Facilitator can submit control"));
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

            // Verify Control Operator is assigned
            if (assignment.getControlOperator() == null || assignment.getControlOperator().isEmpty()) {
                return ResponseEntity.status(400).body(Map.of("success", false, "message", "Control Operator not assigned. Please assign a Control Operator first."));
            }

            String previousStatus = control.getControlStatus();

            // Update control status to indicate it's under Control Operator review
            control.setControlStatus("Control Operator Review");
            controlService.save(control);

            // Add workflow history record
            WorkflowHistory history = new WorkflowHistory();
            history.setControlId(controlId);
            history.setActionType(WorkflowActionType.SUBMIT_TO_OPERATOR);
            history.setPerformedByEmail(currentUser.getMail());
            history.setPerformedByName(currentUser.getDisplayName());
            history.setFromStep(previousStatus != null ? previousStatus : "Facilitator Review");
            history.setToStep("Control Operator Review");
            history.setComments("Control submitted to Control Operator for review");
            workflowHistoryRepository.save(history);

            // Notify Control Operator only
            sendNotificationToRole(control, assignment.getControlOperator(),
                    NotificationTemplateService.TemplateType.FACILITATOR_TO_OPERATOR,
                    false);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Control submitted to Control Operator");
            response.put("controlStatus", control.getControlStatus());

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

            // Only Control Operator can submit to SoQM Lead
            if (!"CONTROL_OPERATOR".equals(currentUser.getRole())) {
                return ResponseEntity.status(403).body(Map.of("success", false, "message", "Only Control Operator can submit to SoQM Lead"));
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

            // Verify SoQM Lead is assigned
            if (assignment.getSoqmLead() == null || assignment.getSoqmLead().isEmpty()) {
                return ResponseEntity.status(400).body(Map.of("success", false, "message", "SoQM Lead not assigned. Please assign a SoQM Lead first."));
            }

            String previousStatus = control.getControlStatus();

            // Update control status to indicate it's under SoQM Lead review
            control.setControlStatus("SoQM Lead Review");
            controlService.save(control);

            // Add workflow history record
            WorkflowHistory history = new WorkflowHistory();
            history.setControlId(controlId);
            history.setActionType(WorkflowActionType.SUBMIT_TO_SOQM_LEAD);
            history.setPerformedByEmail(currentUser.getMail());
            history.setPerformedByName(currentUser.getDisplayName());
            history.setFromStep(previousStatus != null ? previousStatus : "Control Operator Review");
            history.setToStep("SoQM Lead Review");
            history.setComments("Control submitted to SoQM Lead for review");
            workflowHistoryRepository.save(history);

            // Notify SoQM Lead/Delegate only
            boolean resubmitted = previousStatus != null && previousStatus.toLowerCase(Locale.ROOT).contains("returned");
            sendNotificationToRole(control, assignment.getSoqmLead(),
                    NotificationTemplateService.TemplateType.OPERATOR_TO_SOQM,
                    resubmitted);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Control submitted to SoQM Lead");
            response.put("controlStatus", control.getControlStatus());

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

            // Only Control Operator can return to Facilitator
            if (!"CONTROL_OPERATOR".equals(currentUser.getRole())) {
                return ResponseEntity.status(403).body(Map.of("success", false, "message", "Only Control Operator can return control to Facilitator"));
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

            String previousStatus = control.getControlStatus();

            // Update control status back to Facilitator Review
            control.setControlStatus("Facilitator Review");
            controlService.save(control);

            // Add workflow history record
            WorkflowHistory history = new WorkflowHistory();
            history.setControlId(controlId);
            history.setActionType(WorkflowActionType.RETURN_TO_FACILITATOR);
            history.setPerformedByEmail(currentUser.getMail());
            history.setPerformedByName(currentUser.getDisplayName());
            history.setFromStep(previousStatus != null ? previousStatus : "Control Operator Review");
            history.setToStep("Facilitator Review");
            history.setComments(comments != null && !comments.isEmpty() ? comments : "Control returned to Facilitator for revision");
            workflowHistoryRepository.save(history);

            Map<String, Object> response = new HashMap<>();
            response.put("success", true);
            response.put("message", "Control returned to Facilitator");
            response.put("controlStatus", control.getControlStatus());

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "message", "Error returning control: " + e.getMessage()
            ));
        }
    }
}
