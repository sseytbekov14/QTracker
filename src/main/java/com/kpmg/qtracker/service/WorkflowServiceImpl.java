package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.*;
import com.kpmg.qtracker.entity.*;
import com.kpmg.qtracker.enums.*;
import com.kpmg.qtracker.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.kpmg.qtracker.repository.WorkflowHistoryRepository;
import com.kpmg.qtracker.entity.WorkflowHistory;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowServiceImpl implements WorkflowService {
    private final WorkflowStepRepository workflowStepRepository;
    private final ControlAssignmentService controlAssignmentService;
    private final WorkflowHistoryRepository workflowHistoryRepository;
    private final ControlService controlService;
    private final UserService userService;
    private final NotificationService notificationService;

    @Override
    @Transactional
    public void initiateWorkflow(Long controlId, String facilitatorEmail) {
        log.info("Initiating workflow for control: {}, facilitator: {}", controlId, facilitatorEmail);

        // 1. РџРѕР»СѓС‡Р°РµРј assignment С‡С‚РѕР±С‹ Р·РЅР°С‚СЊ РєС‚Рѕ РЅР°Р·РЅР°С‡РµРЅ
        ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(controlId);

        // 2. РЎРѕР·РґР°РµРј РІСЃРµ 4 С€Р°РіР° workflow
        List<WorkflowStep> steps = createWorkflowSteps(controlId, assignment);

        // 3. РЎРѕС…СЂР°РЅСЏРµРј РІСЃРµ С€Р°РіРё
        workflowStepRepository.saveAll(steps);

        // 4. РђРєС‚РёРІРёСЂСѓРµРј РїРµСЂРІС‹Р№ С€Р°Рі (Facilitator)
        WorkflowStep firstStep = steps.get(0);
        firstStep.setStatus(WorkflowStatus.IN_PROGRESS);
        firstStep.setAssignedToEmail(facilitatorEmail);
        workflowStepRepository.save(firstStep);

        // 5. РЎРѕР·РґР°РµРј Р·Р°РїРёСЃСЊ РІ РёСЃС‚РѕСЂРёРё
        createHistoryRecord(controlId, facilitatorEmail,
                WorkflowActionType.INITIATE,
                null,
                "IN_PROGRESS",
                "Workflow initiated by facilitator");

        log.info("Workflow initiated successfully with {} steps", steps.size());
    }

    @Override
    public List<WorkflowButtonDTO> getAvailableButtons(Long controlId, String userEmail) {
        List<WorkflowButtonDTO> buttons = new ArrayList<>();

        try {
            // Use performance_status instead
            Control control = controlService.getControlById(controlId).orElse(null);
            
            String performanceStatus = WorkflowStatus.IN_PROGRESS.name();
            if (control != null && control.getPerformanceStatus() != null) {
                performanceStatus = control.getPerformanceStatus();
            }

            // РџРѕР»СѓС‡Р°РµРј СЂРѕР»СЊ РїРѕР»СЊР·РѕРІР°С‚РµР»СЏ
            Optional<User> userOpt = userService.getUserByEmail(userEmail);
            if (userOpt.isEmpty()) {
                return buttons; // РџРѕР»СЊР·РѕРІР°С‚РµР»СЊ РЅРµ РЅР°Р№РґРµРЅ
            }

            String userRole = userOpt.get().getRole();

            // Get assignment-based roles for this specific control
            List<String> userRolesForControl = controlAssignmentService.getUserRolesForControl(controlId, userEmail);

            // ★ Buttons for FACILITATOR (check assignment, not just global role)
            boolean actAsFacilitator = userRolesForControl.contains("FACILITATOR");
            if (actAsFacilitator && WorkflowStatus.IN_PROGRESS.name().equals(performanceStatus)) {
                buttons.add(new WorkflowButtonDTO(
                        "SUBMIT_FOR_REVIEW",
                        "Submit for Review",
                        "btn-success",
                        false,
                        "Are you sure you want to submit this control for review?"
                ));
            }

            // ★ Buttons for CONTROL_OPERATOR (check assignment, not just global role)
            boolean actAsControlOperator = userRolesForControl.contains("CONTROL_OPERATOR");
            if (actAsControlOperator && WorkflowStatus.REVIEW.name().equals(performanceStatus)) {
                buttons.add(new WorkflowButtonDTO(
                        "SUBMIT_FOR_SOQM",
                        "Submit for SoQM",
                        "btn-success",
                        false,
                        "Submit this control to SOQM Team?"
                ));

                buttons.add(new WorkflowButtonDTO(
                        "RETURN_TO_FACILITATOR",
                        "Return to Facilitator",
                        "btn-warning",
                        true, // С‚СЂРµР±СѓРµС‚ РєРѕРјРјРµРЅС‚Р°СЂРёР№
                        "Please provide reason for returning to Facilitator"
                ));
            }

            // ★ Buttons for SOQM_TEAM (check assignment or global role)
            boolean actAsSoqmLead = userRolesForControl.contains("SOQM_TEAM");
            if (actAsSoqmLead && WorkflowStatus.SOQM_HEAD_REVIEW.name().equals(performanceStatus)) {
                buttons.add(new WorkflowButtonDTO(
                        "SEND_TO_PROCESS_OWNER",
                        "Send to Process Owner",
                        "btn-success",
                        false,
                        "Send this control to Process Owner?"
                ));

                buttons.add(new WorkflowButtonDTO(
                        "SEND_BACK_TO_OPERATOR",
                        "Send back to Operator",
                        "btn-warning",
                        true,
                        "Please provide reason for sending back to Control Operator"
                ));

                buttons.add(new WorkflowButtonDTO(
                        "SOQM_COMMENT",
                        "SoQM Head/Team Comments",
                        "btn-info",
                        true,
                        "Add SOQM comments"
                ));
            }

            // ★ Buttons for PROCESS_OWNER (check assignment, not just global role)
            boolean actAsProcessOwner = userRolesForControl.contains("PROCESS_OWNER");
            if (actAsProcessOwner && WorkflowStatus.PROCESS_OWNER_REVIEW.name().equals(performanceStatus)) {
                buttons.add(new WorkflowButtonDTO(
                        "COMPLETE",
                        "Complete",
                        "btn-success",
                        false,
                        "Mark this control as completed?"
                ));

                buttons.add(new WorkflowButtonDTO(
                        "RETURN_TO_FACILITATOR",
                        "Return to Facilitator",
                        "btn-warning",
                        true,
                        "Please provide reason for returning to Facilitator"
                ));

                buttons.add(new WorkflowButtonDTO(
                        "SEND_FOR_REVISION",
                        "Send for Revision to Control Operator",
                        "btn-info",
                        true,
                        "Please provide revision instructions"
                ));

                buttons.add(new WorkflowButtonDTO(
                        "SUBMIT_FOR_SOQM_REVIEW",
                        "Submit for SoQM Team Review",
                        "btn-info",
                        true,
                        "Submit for additional SOQM review?"
                ));
            }

        } catch (Exception e) {
            log.error("Error getting workflow buttons for control {}: {}", controlId, e.getMessage());
        }

        return buttons;
    }

    @Override
    public WorkflowStatus getCurrentWorkflowStatus(Long controlId) {
        try {
            WorkflowStepDTO currentStep = getCurrentStep(controlId);
            if (currentStep != null) {
                return currentStep.getStatus();
            }

            // Р•СЃР»Рё РЅРµС‚ Р°РєС‚РёРІРЅРѕРіРѕ С€Р°РіР°, РїСЂРѕРІРµСЂСЏРµРј РµСЃС‚СЊ Р»Рё РІРѕРѕР±С‰Рµ workflow
            List<WorkflowStepDTO> steps = getWorkflowSteps(controlId);
            if (steps.isEmpty()) {
                return WorkflowStatus.DRAFT; // РќРµС‚ workflow
            }

            // РџСЂРѕРІРµСЂСЏРµРј РµСЃР»Рё workflow Р·Р°РІРµСЂС€РµРЅ
            boolean allCompleted = steps.stream()
                    .allMatch(step -> step.getStatus() == WorkflowStatus.COMPLETED);
            if (allCompleted) {
                return WorkflowStatus.COMPLETED;
            }

            return WorkflowStatus.DRAFT; // РџРѕ СѓРјРѕР»С‡Р°РЅРёСЋ

        } catch (Exception e) {
            log.error("Error getting workflow status for control {}: {}", controlId, e.getMessage());
            return WorkflowStatus.DRAFT;
        }
    }

    private List<WorkflowStep> createWorkflowSteps(Long controlId, ControlAssignmentDTO assignment) {
        List<WorkflowStep> steps = new ArrayList<>();

        // РЁР°Рі 1: Facilitator
        steps.add(createStep(controlId, WorkflowStepType.FACILITATOR, 1,
                assignment.getFacilitator() != null && !assignment.getFacilitator().isEmpty()
                        ? assignment.getFacilitator().get(0) : null));

        // РЁР°Рі 2: Control Operator
        steps.add(createStep(controlId, WorkflowStepType.CONTROL_OPERATOR, 2,
                assignment.getControlOperator() != null && !assignment.getControlOperator().isEmpty()
                        ? assignment.getControlOperator().get(0) : null));

        // РЁР°Рі 3: SOQM Team
        steps.add(createStep(controlId, WorkflowStepType.SOQM_TEAM, 3,
                assignment.getSoqmLead() != null && !assignment.getSoqmLead().isEmpty()
                        ? assignment.getSoqmLead().get(0) : null));

        // РЁР°Рі 4: Process Owner
        steps.add(createStep(controlId, WorkflowStepType.PROCESS_OWNER, 4,
                assignment.getProcessOwner() != null && !assignment.getProcessOwner().isEmpty()
                        ? assignment.getProcessOwner().get(0) : null));

        return steps;
    }

    private WorkflowStep createStep(Long controlId, WorkflowStepType stepType, int sequenceOrder, String assignedEmail) {
        WorkflowStep step = new WorkflowStep();
        step.setControlId(controlId);
        step.setStepType(stepType);
        step.setSequenceOrder(sequenceOrder);
        step.setAssignedToEmail(assignedEmail);

        // РџРѕР»СѓС‡Р°РµРј РёРјСЏ РїРѕР»СЊР·РѕРІР°С‚РµР»СЏ РµСЃР»Рё email РµСЃС‚СЊ
        if (assignedEmail != null) {
            userService.getUserByEmail(assignedEmail).ifPresent(user -> {
                step.setAssignedToName(user.getDisplayName());
            });
        }

        step.setStatus(WorkflowStatus.DRAFT);
        return step;
    }

    @Override
    public WorkflowStepDTO getCurrentStep(Long controlId) {
        Optional<WorkflowStep> currentStep = workflowStepRepository.findCurrentStep(controlId);
        return currentStep.map(this::convertToDTO).orElse(null);
    }

    @Override
    public List<WorkflowStepDTO> getWorkflowSteps(Long controlId) {
        return workflowStepRepository.findByControlIdOrderBySequenceOrderAsc(controlId)
                .stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Override
    @Transactional
    public WorkflowStepDTO approveStep(WorkflowActionDTO actionDTO, String approverEmail) {
        log.info("Approving step for control: {}, approver: {}", actionDTO.getControlId(), approverEmail);

        // 1. РџРѕР»СѓС‡Р°РµРј С‚РµРєСѓС‰РёР№ С€Р°Рі
        WorkflowStep currentStep = workflowStepRepository.findCurrentStep(actionDTO.getControlId())
                .orElseThrow(() -> new RuntimeException("No active workflow step found"));

        // 2. РџСЂРѕРІРµСЂСЏРµРј С‡С‚Рѕ РїРѕР»СЊР·РѕРІР°С‚РµР»СЊ - РЅР°Р·РЅР°С‡РµРЅРЅС‹Р№ Р°РїСЂСѓРІРµСЂ
        if (!approverEmail.equals(currentStep.getAssignedToEmail())) {
            throw new RuntimeException("User is not assigned to approve this step");
        }

        // 4. Р—Р°РІРµСЂС€Р°РµРј С‚РµРєСѓС‰РёР№ С€Р°Рі
        currentStep.setStatus(WorkflowStatus.COMPLETED);
        currentStep.setCompletedAt(LocalDateTime.now());
        currentStep.setComments(actionDTO.getComments());
        workflowStepRepository.save(currentStep);

        // 5. Р•СЃР»Рё РµСЃС‚СЊ СЃР»РµРґСѓСЋС‰РёР№ С€Р°Рі - Р°РєС‚РёРІРёСЂСѓРµРј РµРіРѕ
        Optional<WorkflowStep> nextStep = workflowStepRepository.findByControlIdAndSequenceOrder(
                actionDTO.getControlId(), currentStep.getSequenceOrder() + 1);

        if (nextStep.isPresent()) {
            WorkflowStep next = nextStep.get();
            next.setStatus(statusForStepType(next.getStepType()));
            workflowStepRepository.save(next);

            // 6. РЎРѕР·РґР°РµРј Р·Р°РїРёСЃСЊ РІ РёСЃС‚РѕСЂРёРё
            createHistoryRecord(actionDTO.getControlId(), approverEmail,
                    WorkflowActionType.APPROVE,
                    statusForStepType(currentStep.getStepType()).name(),
                    statusForStepType(next.getStepType()).name(),
                    actionDTO.getComments());

            // 7. Send notification to next step assignee
            Control control = controlService.getControlById(actionDTO.getControlId()).orElse(null);
            if (control != null && next.getAssignedToEmail() != null) {
                String stepName = next.getStepType().name().replace("_", " ");
                notificationService.sendWorkflowStepNotification(control, next.getAssignedToEmail(),
                    stepName, "Control " + control.getControlId() + " has been forwarded to you for review.");
            }

            return convertToDTO(next);
        } else {
            // Р­С‚Рѕ Р±С‹Р» РїРѕСЃР»РµРґРЅРёР№ С€Р°Рі - workflow Р·Р°РІРµСЂС€РµРЅ
            // 7. РЎРѕР·РґР°РµРј Р·Р°РїРёСЃСЊ РІ РёСЃС‚РѕСЂРёРё
            createHistoryRecord(actionDTO.getControlId(), approverEmail,
                    WorkflowActionType.APPROVE,
                    statusForStepType(currentStep.getStepType()).name(),
                    "COMPLETED",
                    actionDTO.getComments());

            return null; // Workflow Р·Р°РІРµСЂС€РµРЅ
        }
    }

    @Override
    public WorkflowStepDTO returnStep(WorkflowActionDTO actionDTO, String approverEmail) {
        log.info("Returning step for control: {}, approver: {}", actionDTO.getControlId(), approverEmail);

        // 1. РџРѕР»СѓС‡Р°РµРј С‚РµРєСѓС‰РёР№ С€Р°Рі
        WorkflowStep currentStep = workflowStepRepository.findCurrentStep(actionDTO.getControlId())
                .orElseThrow(() -> new RuntimeException("No active workflow step found"));

        // 2. РџСЂРѕРІРµСЂСЏРµРј С‡С‚Рѕ РїРѕР»СЊР·РѕРІР°С‚РµР»СЊ - РЅР°Р·РЅР°С‡РµРЅРЅС‹Р№ Р°РїСЂСѓРІРµСЂ
        if (!approverEmail.equals(currentStep.getAssignedToEmail())) {
            throw new RuntimeException("User is not assigned to approve this step");
        }

        
        

        // 4. в… РџР РћР’Р•Р РЇР•Рњ РџР РђР’Рђ РќРђ Р’РћР—Р’Р РђРў СЃРѕРіР»Р°СЃРЅРѕ С‚Р°Р±Р»РёС†Рµ:
        if (!canUserReturnFromStep(currentStep.getStepType(), approverEmail, actionDTO.getControlId())) {
            throw new RuntimeException("User does not have permission to return from this step");
        }

        // 5. РћРїСЂРµРґРµР»СЏРµРј РЅР° РєР°РєРѕР№ С€Р°Рі РІРѕР·РІСЂР°С‰Р°РµРј (СЃРѕРіР»Р°СЃРЅРѕ С‚Р°Р±Р»РёС†Рµ РїСЂР°РІ)
        String returnToStep = determineReturnToStepAccordingToRights(
                currentStep.getStepType(),
                actionDTO.getReturnToStep(),
                approverEmail,
                actionDTO.getControlId()
        );

        // 6. Р—Р°РІРµСЂС€Р°РµРј С‚РµРєСѓС‰РёР№ С€Р°Рі
        currentStep.setStatus(WorkflowStatus.COMPLETED);
        currentStep.setCompletedAt(LocalDateTime.now());
        currentStep.setReturnReason(actionDTO.getReturnReason());
        currentStep.setReturnedToStep(returnToStep);
        workflowStepRepository.save(currentStep);

        // 7. РђРєС‚РёРІРёСЂСѓРµРј С€Р°Рі РЅР° РєРѕС‚РѕСЂС‹Р№ РІРѕР·РІСЂР°С‰Р°РµРј
        WorkflowStep returnStep = workflowStepRepository.findByControlIdAndStepType(
                        actionDTO.getControlId(), returnToStep)
                .orElseThrow(() -> new RuntimeException("Return step not found: " + returnToStep));

        returnStep.setStatus(statusForStepType(returnStep.getStepType()));
        workflowStepRepository.save(returnStep);

        // 8. РЎРѕР·РґР°РµРј Р·Р°РїРёСЃСЊ РІ РёСЃС‚РѕСЂРёРё
        createHistoryRecord(actionDTO.getControlId(), approverEmail,
                WorkflowActionType.RETURN,
                statusForStepType(currentStep.getStepType()).name(),
                statusForStepType(WorkflowStepType.valueOf(returnToStep)).name(),
                actionDTO.getReturnReason());

        // 9. Send notification to returned step assignee
        Control control = controlService.getControlById(actionDTO.getControlId()).orElse(null);
        if (control != null && returnStep.getAssignedToEmail() != null) {
            String stepName = returnStep.getStepType().name().replace("_", " ");
            String message = "Control " + control.getControlId() + " has been returned to you. Reason: " +
                           (actionDTO.getReturnReason() != null ? actionDTO.getReturnReason() : "No reason provided");
            notificationService.sendWorkflowStepNotification(control, returnStep.getAssignedToEmail(),
                stepName, message);
        }

        return convertToDTO(returnStep);
    }

    private String determineReturnToStepAccordingToRights(WorkflowStepType currentStepType,
                                                          String requestedReturnTo,
                                                          String userEmail,
                                                          Long controlId) {
        // в… РўРђР‘Р›РР¦Рђ РџР РђР’:
        // Control Operator в†’ С‚РѕР»СЊРєРѕ Facilitator
        // SOQM Team в†’ CO РёР»Рё Facilitator
        // Process Owner в†’ Р»СЋР±РѕР№ РїСЂРµРґС‹РґСѓС‰РёР№

        switch (currentStepType) {
            case CONTROL_OPERATOR:
                // РњРѕР¶РµС‚ РІРµСЂРЅСѓС‚СЊ С‚РѕР»СЊРєРѕ Facilitator
                return "FACILITATOR";

            case SOQM_TEAM:
                // РњРѕР¶РµС‚ РІРµСЂРЅСѓС‚СЊ Control Operator РёР»Рё Facilitator
                if (requestedReturnTo != null &&
                        ("CONTROL_OPERATOR".equals(requestedReturnTo) ||
                                "FACILITATOR".equals(requestedReturnTo))) {
                    return requestedReturnTo;
                }
                // РџРѕ СѓРјРѕР»С‡Р°РЅРёСЋ РІРѕР·РІСЂР°С‰Р°РµРј Control Operator
                return "CONTROL_OPERATOR";

            case PROCESS_OWNER:
                // РњРѕР¶РµС‚ РІРµСЂРЅСѓС‚СЊ Р»СЋР±РѕРіРѕ РїСЂРµРґС‹РґСѓС‰РµРіРѕ
                if (requestedReturnTo != null &&
                        ("SOQM_TEAM".equals(requestedReturnTo) ||
                                "CONTROL_OPERATOR".equals(requestedReturnTo) ||
                                "FACILITATOR".equals(requestedReturnTo))) {
                    return requestedReturnTo;
                }
                // РџРѕ СѓРјРѕР»С‡Р°РЅРёСЋ РІРѕР·РІСЂР°С‰Р°РµРј SOQM Team
                return "SOQM_TEAM";

            default:
                throw new RuntimeException("Cannot return from step: " + currentStepType);
        }
    }

    @Override
    public Map<String, Boolean> getUserPermissions(Long controlId, String userEmail) {
        Map<String, Boolean> permissions = new HashMap<>();

        try {
            // в… Check if user is ADMIN first - admins have full permissions
            List<String> userRoles = controlAssignmentService.getUserRolesForControl(controlId, userEmail);
            boolean isAdmin = userRoles.contains("ADMIN");
            
            if (isAdmin) {
                // ADMIN has all permissions
                permissions.put("canEdit", true);
                permissions.put("canComment", true);
                permissions.put("canApprove", true);
                permissions.put("canReturn", true);
                permissions.put("canView", true);
                permissions.put("canEditAll", true);
                permissions.put("isAdmin", true);
                permissions.put("isFacilitator", false);
                permissions.put("isControlOperator", false);
                permissions.put("isSoqmLead", false);
                permissions.put("isSoqmRole", false);
                permissions.put("isProcessOwner", false);
                permissions.put("isOnCurrentStep", false);
                permissions.put("canEditStepsPerformed", false);
                permissions.put("canEditProcessOwnerComments", false);
                return permissions;
            }
            
            // 1. РџРѕР»СѓС‡Р°РµРј С‚РµРєСѓС‰РёР№ СЃС‚Р°С‚СѓСЃ Рё С€Р°Рі
            WorkflowStatus currentStatus = getCurrentWorkflowStatus(controlId);
            WorkflowStepDTO currentStep = getCurrentStep(controlId);

            // 2. РџСЂРѕРІРµСЂСЏРµРј СЂРѕР»Рё РїРѕР»СЊР·РѕРІР°С‚РµР»СЏ РґР»СЏ СЌС‚РѕРіРѕ РєРѕРЅС‚СЂРѕР»СЏ
            boolean isFacilitator = userRoles.contains("FACILITATOR");
            boolean isControlOperator = userRoles.contains("CONTROL_OPERATOR");
            boolean isSoqmLead = userRoles.contains("SOQM_TEAM");
            boolean isProcessOwner = userRoles.contains("PROCESS_OWNER");
            boolean isSoqmRole = userService.getUserByEmail(userEmail)
                    .map(user -> "SOQM_TEAM".equals(user.getRole()))
                    .orElse(false);

            // 3. РџСЂРѕРІРµСЂСЏРµРј РЅР°Р·РЅР°С‡РµРЅ Р»Рё РЅР° С‚РµРєСѓС‰РёР№ С€Р°Рі
            boolean isOnCurrentStep = currentStep != null &&
                    userEmail.equals(currentStep.getAssignedToEmail());

            // 4. Edit control fields
            boolean canEditAll = isSoqmRole;
            permissions.put("canEdit", canEditAll); // Boolean

            // 5. Add comments
            permissions.put("canComment", isOnCurrentStep); // Boolean

            // 6. Approve
            permissions.put("canApprove", isOnCurrentStep); // Boolean

            // 7. Return
            boolean canReturn = isOnCurrentStep && canUserReturnFromStep(
                    currentStep != null ? currentStep.getStepType() : null,
                    userEmail,
                    controlId
            );
            permissions.put("canReturn", canReturn); // Boolean

            // 8. View all data
            permissions.put("canView", true); // Boolean
            permissions.put("canEditAll", canEditAll); // Boolean
            // Check if user is shared viewer for COMPLETED controls
            boolean isSharedCompleted = false;
            {
                ControlAssignmentDTO sharedAssignment = controlAssignmentService.getAssignmentByControlId(controlId);
                if (sharedAssignment != null && sharedAssignment.getControlSharedWith() != null) {
                    isSharedCompleted = sharedAssignment.getControlSharedWith().stream()
                            .anyMatch(e -> e != null && e.equalsIgnoreCase(userEmail));
                }
                Control sharedControl = controlService.getControlById(controlId).orElse(null);
                isSharedCompleted = isSharedCompleted
                        && sharedControl != null
                        && "COMPLETED".equals(sharedControl.getPerformanceStatus());
            }

            boolean canEditSteps = ((isFacilitator || isControlOperator) && !canEditAll)
                    || (isSharedCompleted && (isFacilitator || isControlOperator));
            boolean canEditPOComments = (isProcessOwner && !canEditAll)
                    || (isSharedCompleted && isProcessOwner);

            permissions.put("canEditStepsPerformed", canEditSteps); // Boolean
            permissions.put("canEditProcessOwnerComments", canEditPOComments); // Boolean

            // 9. Р”РѕРїРѕР»РЅРёС‚РµР»СЊРЅР°СЏ РёРЅС„РѕСЂРјР°С†РёСЏ - С‚РѕР¶Рµ Boolean!
            permissions.put("isAdmin", false); // Boolean
            permissions.put("isFacilitator", isFacilitator); // Boolean
            permissions.put("isControlOperator", isControlOperator); // Boolean
            permissions.put("isSoqmLead", isSoqmLead); // Boolean
            permissions.put("isSoqmRole", isSoqmRole); // Boolean
            permissions.put("isProcessOwner", isProcessOwner); // Boolean
            permissions.put("isOnCurrentStep", isOnCurrentStep); // Boolean

            if (currentStep != null) {
                permissions.put("currentStep", true);
            }

        } catch (Exception e) {
            log.error("Error calculating permissions: {}", e.getMessage(), e);
            // Р’РѕР·РІСЂР°С‰Р°РµРј РґРµС„РѕР»С‚РЅС‹Рµ Р·РЅР°С‡РµРЅРёСЏ РїСЂРё РѕС€РёР±РєРµ
            permissions.put("canEdit", false);
            permissions.put("canComment", false);
            permissions.put("canApprove", false);
            permissions.put("canReturn", false);
            permissions.put("canView", true);
        }

        return permissions;
    }

    // в… РќРћР’Р«Р™ РњР•РўРћР”: РџСЂРѕРІРµСЂРєР° РјРѕР¶РµС‚ Р»Рё РїРѕР»СЊР·РѕРІР°С‚РµР»СЊ РІРѕР·РІСЂР°С‰Р°С‚СЊ СЃ С‚РµРєСѓС‰РµРіРѕ С€Р°РіР°
    private boolean canUserReturnFromStep(WorkflowStepType currentStepType,
                                          String userEmail,
                                          Long controlId) {
        switch (currentStepType) {
            case CONTROL_OPERATOR:
                // Control Operator РјРѕР¶РµС‚ РІРѕР·РІСЂР°С‰Р°С‚СЊ С‚РѕР»СЊРєРѕ РµСЃР»Рё РѕРЅ РґРµР№СЃС‚РІРёС‚РµР»СЊРЅРѕ Control Operator
                return controlAssignmentService.isUserControlOperator(controlId, userEmail);

            case SOQM_TEAM:
                // SOQM Team РјРѕР¶РµС‚ РІРѕР·РІСЂР°С‰Р°С‚СЊ С‚РѕР»СЊРєРѕ РµСЃР»Рё РѕРЅ РґРµР№СЃС‚РІРёС‚РµР»СЊРЅРѕ SOQM Team
                return controlAssignmentService.isUserSoqmLead(controlId, userEmail);

            case PROCESS_OWNER:
                // Process Owner РјРѕР¶РµС‚ РІРѕР·РІСЂР°С‰Р°С‚СЊ С‚РѕР»СЊРєРѕ РµСЃР»Рё РѕРЅ РґРµР№СЃС‚РІРёС‚РµР»СЊРЅРѕ Process Owner
                return controlAssignmentService.isUserProcessOwner(controlId, userEmail);

            default:
                return false;
        }
    }

    @Override
    public List<Control> getPendingApprovals(String userEmail) {
        List<WorkflowStep> pendingSteps = workflowStepRepository.findPendingStepsByUser(userEmail);
        return pendingSteps.stream()
                .map(step -> controlService.getControlById(step.getControlId()).orElse(null))
                .filter(control -> control != null)
                .collect(Collectors.toList());
    }

    @Override
    public boolean canUserEditControl(Long controlId, String userEmail) {

        boolean isSoqmRole = userService.getUserByEmail(userEmail)
                .map(user -> "SOQM_TEAM".equals(user.getRole()))
                .orElse(false);
        if (isSoqmRole) {
            return true;
        }
        boolean isFacilitator = controlAssignmentService.isUserFacilitator(controlId, userEmail);
        if (!isFacilitator) {
            return false;
        }

        WorkflowStatus currentStatus = getCurrentWorkflowStatus(controlId);

        boolean canEdit = (currentStatus == WorkflowStatus.DRAFT
                || currentStatus == WorkflowStatus.IN_PROGRESS);

        log.debug("User {} can edit control {}: {} (status: {}, isFacilitator: {})",
                userEmail, controlId, canEdit, currentStatus, isFacilitator);

        return canEdit;
    }

    @Override
    public boolean isCurrentApprover(Long controlId, String userEmail) {
        Optional<WorkflowStep> currentStep = workflowStepRepository.findCurrentStep(controlId);
        return currentStep.filter(step -> userEmail.equals(step.getAssignedToEmail())).isPresent();
    }

    // Р’СЃРїРѕРјРѕРіР°С‚РµР»СЊРЅС‹Рµ РјРµС‚РѕРґС‹
    private WorkflowStepDTO convertToDTO(WorkflowStep step) {
        WorkflowStepDTO dto = new WorkflowStepDTO();
        dto.setId(step.getId());
        dto.setControlId(step.getControlId());
        dto.setStepType(step.getStepType());
        dto.setAssignedToEmail(step.getAssignedToEmail());
        dto.setAssignedToName(step.getAssignedToName());
        dto.setStatus(step.getStatus());
        dto.setAssignedAt(step.getAssignedAt());
        dto.setCompletedAt(step.getCompletedAt());
        dto.setSequenceOrder(step.getSequenceOrder());
        dto.setComments(step.getComments());
        dto.setReturnReason(step.getReturnReason());
        dto.setReturnedToStep(step.getReturnedToStep());
        return dto;
    }

    private WorkflowStatus statusForStepType(WorkflowStepType stepType) {
        return switch (stepType) {
            case FACILITATOR -> WorkflowStatus.IN_PROGRESS;
            case CONTROL_OPERATOR -> WorkflowStatus.REVIEW;
            case SOQM_TEAM -> WorkflowStatus.SOQM_HEAD_REVIEW;
            case PROCESS_OWNER -> WorkflowStatus.PROCESS_OWNER_REVIEW;
        };
    }

    private void createHistoryRecord(Long controlId, String performerEmail,
                                     WorkflowActionType actionType,
                                     String fromStep, String toStep, String comments) {
        WorkflowHistory history = new WorkflowHistory();
        history.setControlId(controlId);
        history.setActionType(actionType);
        history.setPerformedByEmail(performerEmail);

        // РџРѕР»СѓС‡Р°РµРј Рё СѓСЃС‚Р°РЅР°РІР»РёРІР°РµРј РёРјСЏ РїРѕР»СЊР·РѕРІР°С‚РµР»СЏ
        userService.getUserByEmail(performerEmail).ifPresent(user -> {
            history.setPerformedByName(user.getDisplayName());
        });

        history.setFromStep(fromStep);
        history.setToStep(toStep);
        history.setComments(comments);

        // createdAt СѓСЃС‚Р°РЅР°РІР»РёРІР°РµС‚СЃСЏ Р°РІС‚РѕРјР°С‚РёС‡РµСЃРєРё С‡РµСЂРµР· @PrePersist

        workflowHistoryRepository.save(history);
    }

    @Override
    public boolean hasReachedStage(Long controlId, String stageName) {
        if (controlId == null || stageName == null) {
            return false;
        }
        return workflowHistoryRepository.hasReachedStage(controlId, stageName);
    }
}
