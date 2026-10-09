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
    private final ControlPermissionService controlPermissionService;

    @Override
    @Transactional
    public void initiateWorkflow(Long controlId, String facilitatorEmail, User initiatedBy) {
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
        createHistoryRecord(controlId, initiatedBy.getMail(),
                WorkflowActionType.INITIATE,
                null,
                "IN_PROGRESS",
                null);

        // 6. Notify Facilitator(s) that the control has been initiated
        Control control = controlService.getControlById(controlId).orElse(null);
        List<String> facilitators = assignment != null && assignment.getFacilitator() != null
                ? assignment.getFacilitator()
                : List.of(facilitatorEmail);
        notificationService.sendInitiateNotifications(control, facilitators);

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

            Optional<User> userOpt = userService.getUserByEmail(userEmail);
            if (userOpt.isEmpty() || control == null) {
                return buttons;
            }

            // The same rule as the server check of each step (AccessPolicy.isActor)
            ControlPermission permission = controlPermissionService.resolve(control, userOpt.get());

            boolean actAsFacilitator = AccessPolicy.isActor(WorkflowTransition.Actor.FACILITATOR, permission);
            if (actAsFacilitator && WorkflowStatus.IN_PROGRESS.name().equals(performanceStatus)) {
                buttons.add(new WorkflowButtonDTO(
                        "SUBMIT_FOR_REVIEW",
                        "Submit for Review",
                        "btn-success",
                        false,
                        "Are you sure you want to submit this control for review?"
                ));
            }

            boolean actAsControlOperator = AccessPolicy.isActor(WorkflowTransition.Actor.CONTROL_OPERATOR, permission);
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

            // Any SoQM user performs the SoQM steps
            boolean actAsSoqmLead = AccessPolicy.isActor(WorkflowTransition.Actor.SOQM_TEAM, permission);
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

            boolean actAsProcessOwner = AccessPolicy.isActor(WorkflowTransition.Actor.PROCESS_OWNER, permission);
            if (actAsProcessOwner && WorkflowStatus.PROCESS_OWNER_REVIEW.name().equals(performanceStatus)) {
                buttons.add(new WorkflowButtonDTO(
                        "COMPLETE",
                        "Complete",
                        "btn-success",
                        false,
                        "Mark this control as completed?"
                ));

                // The Process Owner returns only to the Control Operator (spec 9.4)
                buttons.add(new WorkflowButtonDTO(
                        "SEND_FOR_REVISION",
                        "Return to Control Operator",
                        "btn-warning",
                        true,
                        "Please provide the reason for returning to the Control Operator"
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
