package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.dto.*;
import com.kpmg.qtracker.entity.*;
import com.kpmg.qtracker.enums.*;
import com.kpmg.qtracker.repository.WorkflowHistoryRepository;
import com.kpmg.qtracker.service.*;
import com.kpmg.qtracker.service.NotificationTemplateService;
import jakarta.servlet.http.HttpSession;
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
    private final WorkflowCommentService workflowCommentService;
    private final WorkflowHistoryRepository workflowHistoryRepository;
    private final NotificationService notificationService;

    @PostMapping("/perform-action")
    @Transactional
    public ResponseEntity<?> performWorkflowAction(@RequestBody WorkflowActionRequest request,
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

            log.info("Workflow action: {} for control: {} by user: {}",
                    action, controlId, userEmail);

            // Получаем текущий performance статус
            String currentPerformanceStatus = performanceService.getPerformanceStatusByControlId(controlId);
            log.info("Current performance status: {}", currentPerformanceStatus);

            // В зависимости от действия меняем статус
            String newStatus = updatePerformanceStatusBasedOnAction(
                    currentPerformanceStatus, action, userEmail, controlId);

            // Обновляем control_status вместо performance_status
            Control control = controlService.getControlById(controlId)
                    .orElseThrow(() -> new RuntimeException("Control not found"));

            control.setControlStatus(newStatus);
            controlService.save(control);

            // Обновляем performance record (без статуса)
            ControlPerformance performance = performanceService.findByControlId(controlId)
                    .orElse(new ControlPerformance());
            performance.setControlId(controlId);
            performance.setUpdatedAt(LocalDate.now());

            // Если есть комментарий - сохраняем
            if (comment != null && !comment.trim().isEmpty()) {
                WorkflowCommentDTO commentDTO = new WorkflowCommentDTO();
                commentDTO.setControlId(controlId);
                commentDTO.setComment(comment);
                commentDTO.setUserEmail(userEmail);
                commentDTO.setUserName(currentUser.getDisplayName());
                commentDTO.setStepType(getCurrentStepFromStatus(currentPerformanceStatus));
                commentDTO.setType(CommentType.GENERAL_COMMENT);

                workflowCommentService.addComment(controlId, commentDTO);
            }

            performanceService.savePerformance(
                    performanceService.convertToDTO(performance, control),
                    control
            );

            // Создаем запись в истории workflow
            createWorkflowHistory(controlId, currentUser, action,
                    currentPerformanceStatus, newStatus, comment);

            // Send workflow notifications based on transition
            sendWorkflowNotifications(control, action, currentPerformanceStatus, newStatus);

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
            case "In Progress":
                if ("SUBMIT_FOR_REVIEW".equals(action) || "INITIATE".equals(action)) {
                    return "Facilitator Review";
                }
                break;

            case "Facilitator Review":
                if ("SUBMIT_TO_CONTROL_OPERATOR".equals(action)) {
                    return "Control Operator Review";
                } else if ("RETURN_TO_FACILITATOR".equals(action)) {
                    return "In Progress";
                }
                break;

            case "Control Operator Review":
                if ("SUBMIT_FOR_SOQM".equals(action) || "SUBMIT_SOQM".equals(action)) {
                    return "SoQM Lead Review";
                } else if ("RETURN_TO_FACILITATOR".equals(action)) {
                    return "Returned by Control Operator";
                }
                break;

            case "SoQM Lead Review":
                if ("SEND_TO_PROCESS_OWNER".equals(action) || "SOQM_COMMENT".equals(action)) {
                    return "Process Owner Review";
                } else if ("SEND_BACK_TO_OPERATOR".equals(action)) {
                    return "Returned by SoQM Lead";
                }
                break;

            case "Process Owner Review":
                if ("COMPLETE".equals(action)) {
                    return "Completed";
                } else if ("RETURN_TO_FACILITATOR".equals(action)) {
                    return "In Progress";
                } else if ("SEND_FOR_REVISION".equals(action)) {
                    return "Returned by Process Owner";
                } else if ("REJECT".equals(action)) {
                    return "Reject";
                }
                break;
        }

        return currentStatus;
    }

    private String getCurrentStepFromStatus(String status) {
        switch (status) {
            case "In Progress": return "FACILITATOR";
            case "Facilitator Review": return "FACILITATOR";
            case "Control Operator Review": return "CONTROL_OPERATOR";
            case "SoQM Lead Review": return "SOQM_LEAD";
            case "Process Owner Review": return "PROCESS_OWNER";
            case "Returned by Control Operator": return "FACILITATOR";
            case "Returned by SoQM Lead": return "CONTROL_OPERATOR";
            case "Returned by Process Owner": return "FACILITATOR";
            case "Completed": return "COMPLETED";
            case "Reject": return "REJECTED";
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
                statusDTO.setReturned(currentStep.getStatus() == WorkflowStatus.RETURNED);
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
    public ResponseEntity<?> approveStep(@RequestBody WorkflowActionDTO actionDTO,
                                         HttpSession session) {
        try {
            User currentUser = (User) session.getAttribute("currentUser");
            if (currentUser == null) {
                return ResponseEntity.status(401).body("User not authenticated");
            }

            workflowService.approveStep(actionDTO, currentUser.getMail());
            return ResponseEntity.ok().build();

        } catch (Exception e) {
            log.error("Error approving step: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @PostMapping("/return")
    public ResponseEntity<?> returnStep(@RequestBody WorkflowActionDTO actionDTO,
                                        HttpSession session) {
        try {
            User currentUser = (User) session.getAttribute("currentUser");
            if (currentUser == null) {
                return ResponseEntity.status(401).body("User not authenticated");
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

    // ========== SOQM LEAD WORKFLOW ENDPOINTS ==========
    
    @PostMapping("/submit-to-process-owner")
    @Transactional
    public ResponseEntity<?> submitToProcessOwner(@RequestParam Long controlId, HttpSession session) {
        try {
            User currentUser = (User) session.getAttribute("currentUser");
            if (currentUser == null) {
                return ResponseEntity.status(401).body("User not authenticated");
            }

            log.info("SoQM Lead {} submitting control {} to Process Owner", currentUser.getMail(), controlId);

            // Get control
            Optional<Control> controlOpt = controlService.getControlById(controlId);
            if (controlOpt.isEmpty()) {
                return ResponseEntity.badRequest().body("Control not found");
            }
            Control control = controlOpt.get();

            // Update control status
            control.setControlStatus("Process Owner Review");
            controlService.save(control);

            // Create workflow history
            WorkflowHistory history = new WorkflowHistory();
            history.setControlId(controlId);
            history.setActionType(WorkflowActionType.SUBMIT_TO_PROCESS_OWNER);
            history.setFromStep("SoQM Lead Review");
            history.setToStep("Process Owner Review");
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

            log.info("SoQM Lead {} returning control {} to Control Operator", currentUser.getMail(), controlId);

            // Get control
            Optional<Control> controlOpt = controlService.getControlById(controlId);
            if (controlOpt.isEmpty()) {
                return ResponseEntity.badRequest().body("Control not found");
            }
            Control control = controlOpt.get();

            // Update control status
            control.setControlStatus("Control Operator Review");
            controlService.save(control);

            // Create workflow history
            WorkflowHistory history = new WorkflowHistory();
            history.setControlId(controlId);
            history.setActionType(WorkflowActionType.RETURN_TO_OPERATOR);
            history.setFromStep("SoQM Lead Review");
            history.setToStep("Control Operator Review");
            history.setPerformedByEmail(currentUser.getMail());
            history.setPerformedByName(currentUser.getDisplayName());
            history.setComments(comments != null && !comments.isEmpty() 
                ? comments 
                : "Control returned to Control Operator for revision");
            history.setCreatedAt(LocalDateTime.now());
            workflowHistoryRepository.save(history);

            // Notify Control Operator only
            notificationService.sendTemplateNotifications(
                    control,
                    assignmentEmails(controlId, "CONTROL_OPERATOR"),
                    NotificationTemplateService.TemplateType.SOQM_TO_OPERATOR_RETURN,
                    false
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

            // Update control status to Completed
            control.setControlStatus("Completed");
            controlService.save(control);

            // Create workflow history
            WorkflowHistory history = new WorkflowHistory();
            history.setControlId(controlId);
            history.setActionType(WorkflowActionType.APPROVE);
            history.setFromStep("Process Owner Review");
            history.setToStep("Completed");
            history.setPerformedByEmail(currentUser.getMail());
            history.setPerformedByName(currentUser.getDisplayName());
            history.setComments("Control completed by Process Owner");
            history.setCreatedAt(LocalDateTime.now());
            workflowHistoryRepository.save(history);

            // Notify all participants
            notificationService.sendTemplateNotifications(
                    control,
                    assignmentEmails(controlId, "ALL"),
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

            log.info("Process Owner {} returning control {} to SoQM Lead", currentUser.getMail(), controlId);

            // Get control
            Optional<Control> controlOpt = controlService.getControlById(controlId);
            if (controlOpt.isEmpty()) {
                return ResponseEntity.badRequest().body("Control not found");
            }
            Control control = controlOpt.get();

            // Update control status
            control.setControlStatus("SoQM Lead Review");
            controlService.save(control);

            // Create workflow history
            WorkflowHistory history = new WorkflowHistory();
            history.setControlId(controlId);
            history.setActionType(WorkflowActionType.RETURN);
            history.setFromStep("Process Owner Review");
            history.setToStep("SoQM Lead Review");
            history.setPerformedByEmail(currentUser.getMail());
            history.setPerformedByName(currentUser.getDisplayName());
            history.setComments(comments != null && !comments.isEmpty() 
                ? comments 
                : "Control returned to SoQM Lead for revision");
            history.setCreatedAt(LocalDateTime.now());
            workflowHistoryRepository.save(history);

            log.info("✅ Control {} returned to SoQM Lead successfully", controlId);
            return ResponseEntity.ok("Control returned to SoQM Lead");

        } catch (Exception e) {
            log.error("❌ Error returning control to SoQM Lead: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @Data
    private static class WorkflowActionRequest {
        private Long controlId;
        private String action;
        private String comment;
    }

    private void sendWorkflowNotifications(Control control, String action, String oldStatus, String newStatus) {
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
            boolean resubmitted = oldStatus != null && oldStatus.toLowerCase(Locale.ROOT).contains("returned");
            notificationService.sendTemplateNotifications(
                    control,
                    assignmentEmails(control.getId(), "SOQM_LEAD"),
                    NotificationTemplateService.TemplateType.OPERATOR_TO_SOQM,
                    resubmitted
            );
            return;
        }

        if ("SEND_BACK_TO_OPERATOR".equals(normalizedAction)) {
            notificationService.sendTemplateNotifications(
                    control,
                    assignmentEmails(control.getId(), "CONTROL_OPERATOR"),
                    NotificationTemplateService.TemplateType.SOQM_TO_OPERATOR_RETURN,
                    false
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
                    assignmentEmails(control.getId(), "ALL"),
                    NotificationTemplateService.TemplateType.COMPLETED_ALL,
                    false
            );
        }
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
            case "SOQM_LEAD":
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
}
