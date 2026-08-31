package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.dto.*;
import com.kpmg.qtracker.entity.*;
import com.kpmg.qtracker.enums.*;
import com.kpmg.qtracker.repository.WorkflowHistoryRepository;
import com.kpmg.qtracker.service.*;
import com.kpmg.qtracker.service.NotificationTemplateService;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/workflow")
@RequiredArgsConstructor
@Slf4j
public class WorkflowController {

    private final WorkflowService workflowService;
    private final IPerformanceService performanceService;
    private final ControlService controlService;
    private final ControlAssignmentService controlAssignmentService;
    private final WorkflowHistoryRepository workflowHistoryRepository;
    private final NotificationService notificationService;
    private final WorkflowRequiredFieldService requiredFieldService;
    private final ControlPermissionService controlPermissionService;

    @PostMapping("/perform-action")
    @Transactional
    public ResponseEntity<?> performWorkflowAction(@Valid @RequestBody WorkflowActionRequest request,
                                                   HttpSession session) {
        try {
            User currentUser = (User) session.getAttribute("currentUser");
            if (currentUser == null) {
                return ResponseEntity.status(401).body("User not authenticated");
            }

            String userEmail = currentUser.getMail();
            Long controlId = request.getControlId();
            String action = request.getAction();
            String comment = request.getComment();

            Control control = controlService.getControlById(controlId)
                    .orElseThrow(() -> new RuntimeException("Control not found"));
            ResponseEntity<?> restrictedResponse = denyWorkflowActionIfRestricted(control, currentUser);
            if (restrictedResponse != null) {
                return restrictedResponse;
            }

            log.info("Workflow action: {} for control: {} by user: {}",
                    action, controlId, userEmail);

            // Получаем текущий performance статус
            String currentPerformanceStatus = performanceService.getPerformanceStatusByControlId(controlId);
            log.info("Current performance status: {}", currentPerformanceStatus);

            // В зависимости от действия меняем статус
            String newStatus = updatePerformanceStatusBasedOnAction(
                    currentPerformanceStatus, action, userEmail, controlId);

            // Обновляем performance_status вместо performance_status
            String normalizedAction = action != null ? action.trim().toUpperCase(Locale.ROOT) : "";
            boolean requiresSteps = Set.of(
                    "SUBMIT_FOR_REVIEW",
                    "SUBMIT_TO_CONTROL_OPERATOR",
                    "SUBMIT_FOR_SOQM",
                    "SUBMIT_SOQM",
                    "SEND_TO_PROCESS_OWNER",
                    "SOQM_COMMENT"
            ).contains(normalizedAction);
            if (requiresSteps) {
                Optional<String> missingField = requiredFieldService.getMissingFieldMessage(control, currentUser);
                if (missingField.isPresent()) {
                    return ResponseEntity.badRequest().body(missingField.get());
                }
            }

            control.setPerformanceStatus(newStatus);

            // Save return comments to control
            if (comment != null && !comment.isEmpty()) {
                if ("RETURN_TO_FACILITATOR".equals(normalizedAction)) {
                    control.setReturnToFacilitatorComment(comment);
                } else if ("SEND_BACK_TO_OPERATOR".equals(normalizedAction)) {
                    control.setReturnToOperatorComment(comment);
                } else if ("RETURN_TO_SOQM_TEAM".equals(normalizedAction)) {
                    control.setReturnToSoqmTeamComment(comment);
                }
            }

            controlService.save(control);

            // Создаем запись в истории workflow
            createWorkflowHistory(controlId, currentUser, action,
                    currentPerformanceStatus, newStatus, comment);

            // Send workflow notifications based on transition
            sendWorkflowNotifications(control, action, currentPerformanceStatus, newStatus, currentUser, comment);

            log.info("Workflow action completed successfully. New status: {}", newStatus);

            return ResponseEntity.ok().build();

        } catch (Exception e) {
            log.error("Error performing workflow action: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    private String updatePerformanceStatusBasedOnAction(String currentStatus,
                                                        String action,
                                                        String userEmail,
                                                        Long controlId) {
        switch (currentStatus) {
            case "DRAFT":
                if ("SUBMIT_FOR_REVIEW".equals(action) || "INITIATE".equals(action)) {
                    return "IN_PROGRESS";
                }
                break;

            case "IN_PROGRESS":
                if ("SUBMIT_TO_CONTROL_OPERATOR".equals(action)) {
                    return "REVIEW";
                }
                break;

            case "REVIEW":
                if ("SUBMIT_FOR_SOQM".equals(action) || "SUBMIT_SOQM".equals(action)) {
                    return "SOQM_HEAD_REVIEW";
                } else if ("RETURN_TO_FACILITATOR".equals(action)) {
                    return "IN_PROGRESS";
                }
                break;

            case "SOQM_HEAD_REVIEW":
                if ("SEND_TO_PROCESS_OWNER".equals(action) || "SOQM_COMMENT".equals(action)) {
                    return "PROCESS_OWNER_REVIEW";
                } else if ("SEND_BACK_TO_OPERATOR".equals(action)) {
                    return "REVIEW";
                }
                break;

            case "PROCESS_OWNER_REVIEW":
                if ("COMPLETE".equals(action)) {
                    return "COMPLETED";
                } else if ("RETURN_TO_FACILITATOR".equals(action)) {
                    return "IN_PROGRESS";
                } else if ("SEND_FOR_REVISION".equals(action)) {
                    return "REVIEW";
                } else if ("REJECT".equals(action)) {
                    return "IN_PROGRESS";
                }
                break;
        }

        return currentStatus;
    }

    private String getCurrentStepFromStatus(String status) {
        switch (status) {
            case "DRAFT": return "FACILITATOR";
            case "IN_PROGRESS": return "FACILITATOR";
            case "REVIEW": return "CONTROL_OPERATOR";
            case "SOQM_HEAD_REVIEW": return "SOQM_TEAM";
            case "PROCESS_OWNER_REVIEW": return "PROCESS_OWNER";
            case "COMPLETED": return "COMPLETED";
            default: return "UNKNOWN";
        }
    }

    private void createWorkflowHistory(Long controlId, User user, String action,
                                       String fromStatus, String toStatus, String comment) {
        WorkflowHistory history = new WorkflowHistory();
        history.setControlId(controlId);
        history.setPerformedByEmail(user.getMail());
        history.setPerformedByName(user.getDisplayName());
        history.setFromStep(fromStatus);
        history.setToStep(toStatus);
        history.setComments(comment);

        // Устанавливаем тип действия
        WorkflowActionType actionType = WorkflowActionType.COMMENT; // По умолчанию
        if (action.contains("SUBMIT") || action.contains("SEND") ||
                action.contains("COMPLETE") || "INITIATE".equals(action)) {
            actionType = WorkflowActionType.APPROVE;
        } else if (action.contains("RETURN") || action.contains("SEND_BACK")) {
            actionType = WorkflowActionType.RETURN;
        }
        history.setActionType(actionType);

        workflowHistoryRepository.save(history);
    }

    @GetMapping("/{controlId}/status")
    public ResponseEntity<WorkflowStatusDTO> getWorkflowStatus(@PathVariable Long controlId,
                                                               HttpSession session) {
        try {
            User currentUser = (User) session.getAttribute("currentUser");
            if (currentUser == null) {
                return ResponseEntity.status(401).build();
            }

            WorkflowStepDTO currentStep = workflowService.getCurrentStep(controlId);
            WorkflowStatusDTO statusDTO = new WorkflowStatusDTO();

            if (currentStep != null) {
                statusDTO.setCurrentStatus(currentStep.getStatus());
                statusDTO.setCurrentStep(currentStep.getStepType().name());
                statusDTO.setAssignedToEmail(currentStep.getAssignedToEmail());
                statusDTO.setAssignedToName(currentStep.getAssignedToName());
                statusDTO.setCompleted(currentStep.getStatus() == WorkflowStatus.COMPLETED);
                statusDTO.setReturned(false);
            }

            // Проверяем права
            statusDTO.setCanEdit(workflowService.canUserEditControl(controlId, currentUser.getMail()));
            statusDTO.setCanApprove(workflowService.isCurrentApprover(controlId, currentUser.getMail()));
            statusDTO.setCanReturn(workflowService.isCurrentApprover(controlId, currentUser.getMail()));

            return ResponseEntity.ok(statusDTO);

        } catch (Exception e) {
            log.error("Error getting workflow status: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/approve")
    public ResponseEntity<?> approveStep(@Valid @RequestBody WorkflowActionDTO actionDTO,
                                         HttpSession session) {
        try {
            User currentUser = (User) session.getAttribute("currentUser");
            if (currentUser == null) {
                return ResponseEntity.status(401).body("User not authenticated");
            }

            Control control = controlService.getControlById(actionDTO.getControlId())
                    .orElseThrow(() -> new RuntimeException("Control not found"));
            ResponseEntity<?> restrictedResponse = denyWorkflowActionIfRestricted(control, currentUser);
            if (restrictedResponse != null) {
                return restrictedResponse;
            }
            workflowService.approveStep(actionDTO, currentUser.getMail());
            return ResponseEntity.ok().build();

        } catch (Exception e) {
            log.error("Error approving step: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/return")
    public ResponseEntity<?> returnStep(@Valid @RequestBody WorkflowActionDTO actionDTO,
                                        HttpSession session) {
        try {
            User currentUser = (User) session.getAttribute("currentUser");
            if (currentUser == null) {
                return ResponseEntity.status(401).body("User not authenticated");
            }

            Control control = controlService.getControlById(actionDTO.getControlId())
                    .orElseThrow(() -> new RuntimeException("Control not found"));
            ResponseEntity<?> restrictedResponse = denyWorkflowActionIfRestricted(control, currentUser);
            if (restrictedResponse != null) {
                return restrictedResponse;
            }
            workflowService.returnStep(actionDTO, currentUser.getMail());
            return ResponseEntity.ok().build();

        } catch (Exception e) {
            log.error("Error returning step: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/my-approvals")
    public ResponseEntity<List<PendingApprovalDTO>> getMyPendingApprovals(HttpSession session) {
        try {
            User currentUser = (User) session.getAttribute("currentUser");
            if (currentUser == null) {
                return ResponseEntity.status(401).build();
            }

            List<Control> pendingControls = workflowService.getPendingApprovals(currentUser.getMail());

            return ResponseEntity.ok(
                    pendingControls.stream()
                            .map(this::convertToPendingApprovalDTO)
                            .collect(Collectors.toList())
            );

        } catch (Exception e) {
            log.error("Error getting pending approvals: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().build();
        }
    }

    private PendingApprovalDTO convertToPendingApprovalDTO(Control control) {
        PendingApprovalDTO dto = new PendingApprovalDTO();
        dto.setControlId(control.getId());
        dto.setControlIdNumber(control.getControlId());
        dto.setComponent(control.getComponent());
        dto.setControlType(control.getControlType());
        dto.setControlDescription(control.getControlDescription());
        return dto;
    }

    // ========== SOQM TEAM WORKFLOW ENDPOINTS ==========
    
    @PostMapping("/submit-to-process-owner")
    @Transactional
    public ResponseEntity<?> submitToProcessOwner(@RequestParam Long controlId, HttpSession session) {
        try {
            User currentUser = (User) session.getAttribute("currentUser");
            if (currentUser == null) {
                return ResponseEntity.status(401).body("User not authenticated");
            }

            log.info("SoQM Team {} submitting control {} to Process Owner", currentUser.getMail(), controlId);

            // Get control
            Optional<Control> controlOpt = controlService.getControlById(controlId);
            if (controlOpt.isEmpty()) {
                return ResponseEntity.badRequest().body("Control not found");
            }
            Control control = controlOpt.get();
            ResponseEntity<?> restrictedResponse = denyWorkflowActionIfRestricted(control, currentUser);
            if (restrictedResponse != null) {
                return restrictedResponse;
            }

            Optional<String> missingField = requiredFieldService.getMissingFieldMessage(control, currentUser);
            if (missingField.isPresent()) {
                return ResponseEntity.badRequest().body(missingField.get());
            }

            // Update control status
            control.setPerformanceStatus("PROCESS_OWNER_REVIEW");
            controlService.save(control);

            // Create workflow history
            WorkflowHistory history = new WorkflowHistory();
            history.setControlId(controlId);
            history.setActionType(WorkflowActionType.SUBMIT_TO_PROCESS_OWNER);
            history.setFromStep("SOQM_HEAD_REVIEW");
            history.setToStep("PROCESS_OWNER_REVIEW");
            history.setPerformedByEmail(currentUser.getMail());
            history.setPerformedByName(currentUser.getDisplayName());
            history.setComments("Control submitted to Process Owner for review");
            history.setCreatedAt(LocalDateTime.now());
            workflowHistoryRepository.save(history);

            // Notify Process Owner only
            notificationService.sendTemplateNotifications(
                    control,
                    assignmentEmails(controlId, "PROCESS_OWNER"),
                    NotificationTemplateService.TemplateType.SOQM_TO_OWNER,
                    false
            );

            log.info("✅ Control {} submitted to Process Owner successfully", controlId);
            return ResponseEntity.ok("Control submitted to Process Owner");

        } catch (Exception e) {
            log.error("❌ Error submitting control to Process Owner: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/return-to-operator")
    @Transactional
    public ResponseEntity<?> returnToOperator(@RequestParam Long controlId,
                                              @RequestParam(required = false) String comments,
                                              HttpSession session) {
        try {
            User currentUser = (User) session.getAttribute("currentUser");
            if (currentUser == null) {
                return ResponseEntity.status(401).body("User not authenticated");
            }

            log.info("SoQM Team {} returning control {} to Control Operator", currentUser.getMail(), controlId);

            // Get control
            Optional<Control> controlOpt = controlService.getControlById(controlId);
            if (controlOpt.isEmpty()) {
                return ResponseEntity.badRequest().body("Control not found");
            }
            Control control = controlOpt.get();
            ResponseEntity<?> restrictedResponse = denyWorkflowActionIfRestricted(control, currentUser);
            if (restrictedResponse != null) {
                return restrictedResponse;
            }

            // Validate comment length
            if (comments != null && comments.length() > 2000) {
                return ResponseEntity.badRequest().body("Comment is too long. Maximum 2000 characters allowed.");
            }

            // Update control status
            control.setPerformanceStatus("REVIEW");
            if (comments != null && !comments.isEmpty()) {
                control.setReturnToOperatorComment(comments);
            }
            controlService.save(control);

            // Create workflow history
            WorkflowHistory history = new WorkflowHistory();
            history.setControlId(controlId);
            history.setActionType(WorkflowActionType.RETURN_TO_OPERATOR);
            history.setFromStep("SOQM_HEAD_REVIEW");
            history.setToStep("REVIEW");
            history.setPerformedByEmail(currentUser.getMail());
            history.setPerformedByName(currentUser.getDisplayName());
            history.setComments(comments != null && !comments.isEmpty() 
                ? comments 
                : "Control returned to Control Operator for revision");
            history.setCreatedAt(LocalDateTime.now());
            workflowHistoryRepository.save(history);

            // Notify Control Operator only
            List<String> recipients = assignmentEmails(controlId, "CONTROL_OPERATOR");
            String currentEmail = currentUser.getMail();
            if (currentEmail != null) {
                recipients.removeIf(email -> email != null && email.equalsIgnoreCase(currentEmail));
            }
            notificationService.sendReturnNotifications(
                    control,
                    recipients,
                    currentUser.getRole(),
                    currentUser.getDisplayName(),
                    "Control Operator",
                    comments,
                    "RETURN_TO_OPERATOR"
            );

            log.info("✅ Control {} returned to Control Operator successfully", controlId);
            return ResponseEntity.ok("Control returned to Control Operator");

        } catch (Exception e) {
            log.error("❌ Error returning control to Operator: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/complete-control")
    @Transactional
    public ResponseEntity<?> completeControl(@RequestParam Long controlId,
                                            HttpSession session) {
        try {
            User currentUser = (User) session.getAttribute("currentUser");
            if (currentUser == null) {
                return ResponseEntity.status(401).body("User not authenticated");
            }

            log.info("Process Owner {} completing control {}", currentUser.getMail(), controlId);

            // Get control
            Optional<Control> controlOpt = controlService.getControlById(controlId);
            if (controlOpt.isEmpty()) {
                return ResponseEntity.badRequest().body("Control not found");
            }
            Control control = controlOpt.get();
            ResponseEntity<?> restrictedResponse = denyWorkflowActionIfRestricted(control, currentUser);
            if (restrictedResponse != null) {
                return restrictedResponse;
            }

            // Update control status to Completed
            control.setPerformanceStatus("COMPLETED");
            controlService.save(control);

            // Create workflow history
            WorkflowHistory history = new WorkflowHistory();
            history.setControlId(controlId);
            history.setActionType(WorkflowActionType.APPROVE);
            history.setFromStep("PROCESS_OWNER_REVIEW");
            history.setToStep("COMPLETED");
            history.setPerformedByEmail(currentUser.getMail());
            history.setPerformedByName(currentUser.getDisplayName());
            history.setComments("Control completed by Process Owner");
            history.setCreatedAt(LocalDateTime.now());
            workflowHistoryRepository.save(history);

            // Notify control participants (excluding process owner and shared users)
            notificationService.sendTemplateNotifications(
                    control,
                    assignmentEmails(controlId, "COMPLETED"),
                    NotificationTemplateService.TemplateType.COMPLETED_ALL,
                    false
            );

            log.info("✅ Control {} completed successfully", controlId);
            return ResponseEntity.ok("Control completed");

        } catch (Exception e) {
            log.error("❌ Error completing control: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/return-to-soqm-lead")
    @Transactional
    public ResponseEntity<?> returnToSoqmLead(@RequestParam Long controlId,
                                             @RequestParam(required = false) String comments,
                                             HttpSession session) {
        try {
            User currentUser = (User) session.getAttribute("currentUser");
            if (currentUser == null) {
                return ResponseEntity.status(401).body("User not authenticated");
            }

            log.info("Process Owner {} returning control {} to SoQM Team", currentUser.getMail(), controlId);

            // Get control
            Optional<Control> controlOpt = controlService.getControlById(controlId);
            if (controlOpt.isEmpty()) {
                return ResponseEntity.badRequest().body("Control not found");
            }
            Control control = controlOpt.get();
            ResponseEntity<?> restrictedResponse = denyWorkflowActionIfRestricted(control, currentUser);
            if (restrictedResponse != null) {
                return restrictedResponse;
            }

            // Validate comment length
            if (comments != null && comments.length() > 2000) {
                return ResponseEntity.badRequest().body("Comment is too long. Maximum 2000 characters allowed.");
            }

            // Update control status
            control.setPerformanceStatus("SOQM_HEAD_REVIEW");
            if (comments != null && !comments.isEmpty()) {
                control.setReturnToSoqmTeamComment(comments);
            }
            controlService.save(control);

            // Create workflow history
            WorkflowHistory history = new WorkflowHistory();
            history.setControlId(controlId);
            history.setActionType(WorkflowActionType.RETURN);
            history.setFromStep("PROCESS_OWNER_REVIEW");
            history.setToStep("SOQM_HEAD_REVIEW");
            history.setPerformedByEmail(currentUser.getMail());
            history.setPerformedByName(currentUser.getDisplayName());
            history.setComments(comments != null && !comments.isEmpty() 
                ? comments 
                : "Control returned to SoQM Team for revision");
            history.setCreatedAt(LocalDateTime.now());
            workflowHistoryRepository.save(history);

            ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(controlId);
            List<String> recipients = new ArrayList<>();
            if (assignment != null && assignment.getSoqmLead() != null) {
                recipients.addAll(assignment.getSoqmLead());
            }
            String currentEmail = currentUser.getMail();
            if (currentEmail != null) {
                recipients.removeIf(email -> email != null && email.equalsIgnoreCase(currentEmail));
            }
            notificationService.sendReturnNotifications(
                    control,
                    recipients,
                    currentUser.getRole(),
                    currentUser.getDisplayName(),
                    "SoQM Team",
                    comments,
                    "RETURN_TO_SOQM_TEAM"
            );

            log.info("✅ Control {} returned to SoQM Team successfully", controlId);
            return ResponseEntity.ok("Control returned to SoQM Team");

        } catch (Exception e) {
            log.error("❌ Error returning control to SoQM Team: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @Data
    private static class WorkflowActionRequest {
        private Long controlId;
        private String action;
        @Size(max = 2000, message = "Comment must be at most 2000 characters")
        private String comment;
    }

    private void sendWorkflowNotifications(Control control, String action, String oldStatus, String newStatus) {
        sendWorkflowNotifications(control, action, oldStatus, newStatus, null, null);
    }

    private void sendWorkflowNotifications(Control control,
                                           String action,
                                           String oldStatus,
                                           String newStatus,
                                           User currentUser,
                                           String comment) {
        if (control == null || action == null) {
            return;
        }
        String normalizedAction = action.trim().toUpperCase(Locale.ROOT);

        if ("SUBMIT_TO_CONTROL_OPERATOR".equals(normalizedAction)) {
            notificationService.sendTemplateNotifications(
                    control,
                    assignmentEmails(control.getId(), "CONTROL_OPERATOR"),
                    NotificationTemplateService.TemplateType.FACILITATOR_TO_OPERATOR,
                    false
            );
            return;
        }

        if ("SUBMIT_FOR_SOQM".equals(normalizedAction) || "SUBMIT_SOQM".equals(normalizedAction)) {
            boolean resubmitted = false;
            notificationService.sendTemplateNotifications(
                    control,
                    assignmentEmails(control.getId(), "SOQM_TEAM"),
                    NotificationTemplateService.TemplateType.OPERATOR_TO_SOQM,
                    resubmitted
            );
            return;
        }

        if ("SEND_BACK_TO_OPERATOR".equals(normalizedAction)) {
            notificationService.sendReturnNotifications(
                    control,
                    recipientsWithoutActor(assignmentEmails(control.getId(), "CONTROL_OPERATOR"),
                            currentUser != null ? currentUser.getMail() : null),
                    currentUser != null ? currentUser.getRole() : null,
                    currentUser != null ? currentUser.getDisplayName() : null,
                    "Control Operator",
                    firstNonBlank(comment, control.getReturnToOperatorComment()),
                    "RETURN_TO_OPERATOR"
            );
            return;
        }

        if ("RETURN_TO_FACILITATOR".equals(normalizedAction)) {
            notificationService.sendReturnNotifications(
                    control,
                    recipientsWithoutActor(assignmentEmails(control.getId(), "FACILITATOR"),
                            currentUser != null ? currentUser.getMail() : null),
                    currentUser != null ? currentUser.getRole() : null,
                    currentUser != null ? currentUser.getDisplayName() : null,
                    "Facilitator",
                    firstNonBlank(comment, control.getReturnToFacilitatorComment()),
                    "RETURN_TO_FACILITATOR"
            );
            return;
        }

        if ("RETURN_TO_SOQM_TEAM".equals(normalizedAction)) {
            notificationService.sendReturnNotifications(
                    control,
                    recipientsWithoutActor(assignmentEmails(control.getId(), "SOQM_TEAM"),
                            currentUser != null ? currentUser.getMail() : null),
                    currentUser != null ? currentUser.getRole() : null,
                    currentUser != null ? currentUser.getDisplayName() : null,
                    "SoQM Team",
                    firstNonBlank(comment, control.getReturnToSoqmTeamComment()),
                    "RETURN_TO_SOQM_TEAM"
            );
            return;
        }

        if ("SEND_TO_PROCESS_OWNER".equals(normalizedAction) || "SOQM_COMMENT".equals(normalizedAction)) {
            notificationService.sendTemplateNotifications(
                    control,
                    assignmentEmails(control.getId(), "PROCESS_OWNER"),
                    NotificationTemplateService.TemplateType.SOQM_TO_OWNER,
                    false
            );
            return;
        }

        if ("COMPLETE".equals(normalizedAction)) {
            notificationService.sendTemplateNotifications(
                    control,
                    assignmentEmails(control.getId(), "COMPLETED"),
                    NotificationTemplateService.TemplateType.COMPLETED_ALL,
                    false
            );
        }
    }

    private List<String> recipientsWithoutActor(List<String> recipients, String actorEmail) {
        if (recipients == null || recipients.isEmpty() || actorEmail == null || actorEmail.isBlank()) {
            return recipients == null ? List.of() : recipients;
        }
        List<String> filtered = new ArrayList<>(recipients);
        filtered.removeIf(email -> email != null && email.equalsIgnoreCase(actorEmail));
        return filtered;
    }

    private String firstNonBlank(String first, String second) {
        if (first != null && !first.isBlank()) {
            return first;
        }
        if (second != null && !second.isBlank()) {
            return second;
        }
        return null;
    }

    private List<String> assignmentEmails(Long controlId, String role) {
        ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(controlId);
        Set<String> recipients = new LinkedHashSet<>();
        if (assignment == null) {
            return List.of();
        }
        switch (role) {
            case "FACILITATOR":
                addAll(recipients, assignment.getFacilitator());
                break;
            case "CONTROL_OPERATOR":
                addAll(recipients, assignment.getControlOperator());
                break;
            case "SOQM_TEAM":
                addAll(recipients, assignment.getSoqmLead());
                break;
            case "PROCESS_OWNER":
                addAll(recipients, assignment.getProcessOwner());
                break;
            case "ALL":
                addAll(recipients, assignment.getFacilitator());
                addAll(recipients, assignment.getControlOperator());
                addAll(recipients, assignment.getSoqmLead());
                addAll(recipients, assignment.getProcessOwner());
                break;
            case "COMPLETED":
                addAll(recipients, assignment.getFacilitator());
                addAll(recipients, assignment.getControlOperator());
                addAll(recipients, assignment.getSoqmLead());
                break;
            default:
                break;
        }
        return new ArrayList<>(recipients);
    }

    private void addAll(Set<String> target, List<String> items) {
        if (items == null) {
            return;
        }
        for (String item : items) {
            if (item != null && !item.isBlank()) {
                target.add(item.trim());
            }
        }
    }

    private void sendRoleNotification(Control control, List<String> recipients, String stepName, String message) {
        if (control == null || recipients == null || recipients.isEmpty()) {
            return;
        }
        log.info("Workflow notification: control={}, step={}, recipients={}",
                control.getControlId(), stepName, recipients.size());
        notificationService.sendWorkflowStepNotifications(control, recipients, stepName, message);
    }

    private ResponseEntity<?> denyWorkflowActionIfRestricted(Control control, User currentUser) {
        ControlPermission permission = controlPermissionService.resolve(control, currentUser);
        if (!permission.canView()) {
            return ResponseEntity.status(403).body("Forbidden");
        }
        if (!permission.canUseWorkflowActions()) {
            return ResponseEntity.status(403)
                    .body("Workflow actions are disabled for shared users on completed controls");
        }
        return null;
    }
}
