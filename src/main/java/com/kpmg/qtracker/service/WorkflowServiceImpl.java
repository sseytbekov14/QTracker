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

        // 1. Получаем assignment чтобы знать кто назначен
        ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(controlId);

        // 2. Создаем все 4 шага workflow
        List<WorkflowStep> steps = createWorkflowSteps(controlId, assignment);

        // 3. Сохраняем все шаги
        workflowStepRepository.saveAll(steps);

        // 4. Активируем первый шаг (Facilitator)
        WorkflowStep firstStep = steps.get(0);
        firstStep.setStatus(WorkflowStatus.FACILITATOR_REVIEW);
        firstStep.setAssignedToEmail(facilitatorEmail);
        workflowStepRepository.save(firstStep);

        // 5. Создаем запись в истории
        createHistoryRecord(controlId, facilitatorEmail,
                WorkflowActionType.INITIATE,
                null,
                "FACILITATOR_REVIEW",
                "Workflow initiated by facilitator");

        // 6. Send notifications to all assigned roles (facilitator + operator + SOQM lead + process owner)
        Control control = controlService.getControlById(controlId).orElse(null);
        if (control != null) {
            List<String> recipients = new ArrayList<>();
            if (assignment.getFacilitator() != null) {
                recipients.addAll(assignment.getFacilitator());
            }
            if (assignment.getControlOperator() != null) {
                recipients.addAll(assignment.getControlOperator());
            }
            if (assignment.getSoqmLead() != null) {
                recipients.addAll(assignment.getSoqmLead());
            }
            if (assignment.getProcessOwner() != null) {
                recipients.addAll(assignment.getProcessOwner());
            }

            if (!recipients.isEmpty()) {
                // Remove duplicates while preserving order
                List<String> uniqueRecipients = recipients.stream()
                        .filter(Objects::nonNull)
                        .distinct()
                        .collect(Collectors.toList());
                notificationService.sendInitiateNotifications(control, uniqueRecipients);
            }
        }

        log.info("Workflow initiated successfully with {} steps", steps.size());
    }

    @Override
    public List<WorkflowButtonDTO> getAvailableButtons(Long controlId, String userEmail) {
        List<WorkflowButtonDTO> buttons = new ArrayList<>();

        try {
            // Use control_status instead
            Control control = controlService.getControlById(controlId).orElse(null);
            
            String performanceStatus = "In Progress";
            if (control != null && control.getControlStatus() != null) {
                performanceStatus = control.getControlStatus();
            }

            // Получаем роль пользователя
            Optional<User> userOpt = userService.getUserByEmail(userEmail);
            if (userOpt.isEmpty()) {
                return buttons; // Пользователь не найден
            }

            String userRole = userOpt.get().getRole();

            // ★ Кнопки для FACILITATOR
            if ("FACILITATOR".equals(userRole) && "In Progress".equals(performanceStatus)) {
                buttons.add(new WorkflowButtonDTO(
                        "SUBMIT_FOR_REVIEW",
                        "Submit for Review",
                        "btn-success",
                        false,
                        "Are you sure you want to submit this control for review?"
                ));
            }

            // ★ Кнопки для CONTROL_OPERATOR
            if ("CONTROL_OPERATOR".equals(userRole) && "Control Operator Review".equals(performanceStatus)) {
                buttons.add(new WorkflowButtonDTO(
                        "SUBMIT_FOR_SOQM",
                        "Submit for SoQM",
                        "btn-success",
                        false,
                        "Submit this control to SOQM Lead?"
                ));

                buttons.add(new WorkflowButtonDTO(
                        "RETURN_TO_FACILITATOR",
                        "Return to Facilitator",
                        "btn-warning",
                        true, // требует комментарий
                        "Please provide reason for returning to Facilitator"
                ));
            }

            // ★ Кнопки для SOQM_LEAD
            if ("SOQM_LEAD".equals(userRole) && "SoQM Head Review".equals(performanceStatus)) {
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

            // ★ Кнопки для PROCESS_OWNER
            if ("PROCESS_OWNER".equals(userRole) && "Process Owner Review".equals(performanceStatus)) {
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

            // Если нет активного шага, проверяем есть ли вообще workflow
            List<WorkflowStepDTO> steps = getWorkflowSteps(controlId);
            if (steps.isEmpty()) {
                return WorkflowStatus.NOT_STARTED; // Нет workflow
            }

            // Проверяем если workflow завершен
            boolean allCompleted = steps.stream()
                    .allMatch(step -> step.getStatus() == WorkflowStatus.COMPLETED);
            if (allCompleted) {
                return WorkflowStatus.COMPLETED;
            }

            return WorkflowStatus.DRAFT; // По умолчанию

        } catch (Exception e) {
            log.error("Error getting workflow status for control {}: {}", controlId, e.getMessage());
            return WorkflowStatus.NOT_STARTED;
        }
    }

    private List<WorkflowStep> createWorkflowSteps(Long controlId, ControlAssignmentDTO assignment) {
        List<WorkflowStep> steps = new ArrayList<>();

        // Шаг 1: Facilitator
        steps.add(createStep(controlId, WorkflowStepType.FACILITATOR, 1,
                assignment.getFacilitator() != null && !assignment.getFacilitator().isEmpty()
                        ? assignment.getFacilitator().get(0) : null));

        // Шаг 2: Control Operator
        steps.add(createStep(controlId, WorkflowStepType.CONTROL_OPERATOR, 2,
                assignment.getControlOperator() != null && !assignment.getControlOperator().isEmpty()
                        ? assignment.getControlOperator().get(0) : null));

        // Шаг 3: SOQM Lead
        steps.add(createStep(controlId, WorkflowStepType.SOQM_LEAD, 3,
                assignment.getSoqmLead() != null && !assignment.getSoqmLead().isEmpty()
                        ? assignment.getSoqmLead().get(0) : null));

        // Шаг 4: Process Owner
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

        // Получаем имя пользователя если email есть
        if (assignedEmail != null) {
            userService.getUserByEmail(assignedEmail).ifPresent(user -> {
                step.setAssignedToName(user.getDisplayName());
            });
        }

        step.setStatus(WorkflowStatus.NOT_STARTED);
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

        // 1. Получаем текущий шаг
        WorkflowStep currentStep = workflowStepRepository.findCurrentStep(actionDTO.getControlId())
                .orElseThrow(() -> new RuntimeException("No active workflow step found"));

        // 2. Проверяем что пользователь - назначенный апрувер
        if (!approverEmail.equals(currentStep.getAssignedToEmail())) {
            throw new RuntimeException("User is not assigned to approve this step");
        }

        // 3. VALIDATION: If this is Control Operator step, check if controlOperatorsProgram is filled
        if (currentStep.getStepType().name().equals("CONTROL_OPERATOR")) {
            Control control = controlService.getControlById(actionDTO.getControlId())
                    .orElseThrow(() -> new RuntimeException("Control not found"));
            
            if (control.getControlOperatorsProgram() == null || 
                control.getControlOperatorsProgram().trim().isEmpty()) {
                throw new RuntimeException("VALIDATION_ERROR: Control Operator's Program must be filled before submitting");
            }
            log.info("✅ Control Operator's Program validation passed for control: {}", actionDTO.getControlId());
        }
        
        // 3b. VALIDATION: If this is SoQM Lead step, check if soqmHeadComments is filled
        if (currentStep.getStepType().name().equals("SOQM_LEAD")) {
            Control control = controlService.getControlById(actionDTO.getControlId())
                    .orElseThrow(() -> new RuntimeException("Control not found"));
            
            if (control.getSoqmHeadComments() == null || 
                control.getSoqmHeadComments().trim().isEmpty()) {
                throw new RuntimeException("VALIDATION_ERROR: SoQM Head/Team Comments must be filled before submitting");
            }
            log.info("✅ SoQM Head/Team Comments validation passed for control: {}", actionDTO.getControlId());
        }
        
        // 3c. VALIDATION: If this is Process Owner step, check if processOwnerComments is filled
        if (currentStep.getStepType().name().equals("PROCESS_OWNER")) {
            Control control = controlService.getControlById(actionDTO.getControlId())
                    .orElseThrow(() -> new RuntimeException("Control not found"));
            
            if (control.getProcessOwnerComments() == null || 
                control.getProcessOwnerComments().trim().isEmpty()) {
                throw new RuntimeException("VALIDATION_ERROR: Process Owner Comments must be filled before completing");
            }
            log.info("✅ Process Owner Comments validation passed for control: {}", actionDTO.getControlId());
        }

        // 4. Завершаем текущий шаг
        currentStep.setStatus(WorkflowStatus.COMPLETED);
        currentStep.setCompletedAt(LocalDateTime.now());
        currentStep.setComments(actionDTO.getComments());
        workflowStepRepository.save(currentStep);

        // 5. Если есть следующий шаг - активируем его
        Optional<WorkflowStep> nextStep = workflowStepRepository.findByControlIdAndSequenceOrder(
                actionDTO.getControlId(), currentStep.getSequenceOrder() + 1);

        if (nextStep.isPresent()) {
            WorkflowStep next = nextStep.get();
            next.setStatus(WorkflowStatus.valueOf(next.getStepType().name() + "_REVIEW"));
            workflowStepRepository.save(next);

            // 6. Создаем запись в истории
            createHistoryRecord(actionDTO.getControlId(), approverEmail,
                    WorkflowActionType.APPROVE,
                    currentStep.getStepType().name(),
                    next.getStepType().name(),
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
            // Это был последний шаг - workflow завершен
            // 7. Создаем запись в истории
            createHistoryRecord(actionDTO.getControlId(), approverEmail,
                    WorkflowActionType.APPROVE,
                    currentStep.getStepType().name(),
                    "COMPLETED",
                    actionDTO.getComments());

            return null; // Workflow завершен
        }
    }

    @Override
    public WorkflowStepDTO returnStep(WorkflowActionDTO actionDTO, String approverEmail) {
        log.info("Returning step for control: {}, approver: {}", actionDTO.getControlId(), approverEmail);

        // 1. Получаем текущий шаг
        WorkflowStep currentStep = workflowStepRepository.findCurrentStep(actionDTO.getControlId())
                .orElseThrow(() -> new RuntimeException("No active workflow step found"));

        // 2. Проверяем что пользователь - назначенный апрувер
        if (!approverEmail.equals(currentStep.getAssignedToEmail())) {
            throw new RuntimeException("User is not assigned to approve this step");
        }

        // 3. VALIDATION: If this is Control Operator step, check if controlOperatorsProgram is filled
        if (currentStep.getStepType().name().equals("CONTROL_OPERATOR")) {
            Control control = controlService.getControlById(actionDTO.getControlId())
                    .orElseThrow(() -> new RuntimeException("Control not found"));
            
            if (control.getControlOperatorsProgram() == null || 
                control.getControlOperatorsProgram().trim().isEmpty()) {
                throw new RuntimeException("VALIDATION_ERROR: Control Operator's Program must be filled before returning");
            }
            log.info("✅ Control Operator's Program validation passed for control: {}", actionDTO.getControlId());
        }
        
        // 3b. VALIDATION: If this is SoQM Lead step, check if soqmHeadComments is filled
        if (currentStep.getStepType().name().equals("SOQM_LEAD")) {
            Control control = controlService.getControlById(actionDTO.getControlId())
                    .orElseThrow(() -> new RuntimeException("Control not found"));
            
            if (control.getSoqmHeadComments() == null || 
                control.getSoqmHeadComments().trim().isEmpty()) {
                throw new RuntimeException("VALIDATION_ERROR: SoQM Head/Team Comments must be filled before returning");
            }
            log.info("✅ SoQM Head/Team Comments validation passed for control: {}", actionDTO.getControlId());
        }
        
        // 3c. VALIDATION: If this is Process Owner step, check if processOwnerComments is filled
        if (currentStep.getStepType().name().equals("PROCESS_OWNER")) {
            Control control = controlService.getControlById(actionDTO.getControlId())
                    .orElseThrow(() -> new RuntimeException("Control not found"));
            
            if (control.getProcessOwnerComments() == null || 
                control.getProcessOwnerComments().trim().isEmpty()) {
                throw new RuntimeException("VALIDATION_ERROR: Process Owner Comments must be filled before returning");
            }
            log.info("✅ Process Owner Comments validation passed for control: {}", actionDTO.getControlId());
        }

        // 4. ★ ПРОВЕРЯЕМ ПРАВА НА ВОЗВРАТ согласно таблице:
        if (!canUserReturnFromStep(currentStep.getStepType(), approverEmail, actionDTO.getControlId())) {
            throw new RuntimeException("User does not have permission to return from this step");
        }

        // 5. Определяем на какой шаг возвращаем (согласно таблице прав)
        String returnToStep = determineReturnToStepAccordingToRights(
                currentStep.getStepType(),
                actionDTO.getReturnToStep(),
                approverEmail,
                actionDTO.getControlId()
        );

        // 6. Завершаем текущий шаг с статусом RETURNED
        currentStep.setStatus(WorkflowStatus.RETURNED);
        currentStep.setCompletedAt(LocalDateTime.now());
        currentStep.setReturnReason(actionDTO.getReturnReason());
        currentStep.setReturnedToStep(returnToStep);
        workflowStepRepository.save(currentStep);

        // 7. Активируем шаг на который возвращаем
        WorkflowStep returnStep = workflowStepRepository.findByControlIdAndStepType(
                        actionDTO.getControlId(), returnToStep)
                .orElseThrow(() -> new RuntimeException("Return step not found: " + returnToStep));

        returnStep.setStatus(WorkflowStatus.valueOf(returnStep.getStepType().name() + "_REVIEW"));
        workflowStepRepository.save(returnStep);

        // 8. Создаем запись в истории
        createHistoryRecord(actionDTO.getControlId(), approverEmail,
                WorkflowActionType.RETURN,
                currentStep.getStepType().name(),
                returnToStep,
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
        // ★ ТАБЛИЦА ПРАВ:
        // Control Operator → только Facilitator
        // SOQM Lead → CO или Facilitator
        // Process Owner → любой предыдущий

        switch (currentStepType) {
            case CONTROL_OPERATOR:
                // Может вернуть только Facilitator
                return "FACILITATOR";

            case SOQM_LEAD:
                // Может вернуть Control Operator или Facilitator
                if (requestedReturnTo != null &&
                        ("CONTROL_OPERATOR".equals(requestedReturnTo) ||
                                "FACILITATOR".equals(requestedReturnTo))) {
                    return requestedReturnTo;
                }
                // По умолчанию возвращаем Control Operator
                return "CONTROL_OPERATOR";

            case PROCESS_OWNER:
                // Может вернуть любого предыдущего
                if (requestedReturnTo != null &&
                        ("SOQM_LEAD".equals(requestedReturnTo) ||
                                "CONTROL_OPERATOR".equals(requestedReturnTo) ||
                                "FACILITATOR".equals(requestedReturnTo))) {
                    return requestedReturnTo;
                }
                // По умолчанию возвращаем SOQM Lead
                return "SOQM_LEAD";

            default:
                throw new RuntimeException("Cannot return from step: " + currentStepType);
        }
    }

    @Override
    public Map<String, Boolean> getUserPermissions(Long controlId, String userEmail) {
        Map<String, Boolean> permissions = new HashMap<>();

        try {
            // ★ Check if user is ADMIN first - admins have full permissions
            List<String> userRoles = controlAssignmentService.getUserRolesForControl(controlId, userEmail);
            boolean isAdmin = userRoles.contains("ADMIN");
            
            if (isAdmin) {
                // ADMIN has all permissions
                permissions.put("canEdit", true);
                permissions.put("canComment", true);
                permissions.put("canApprove", true);
                permissions.put("canReturn", true);
                permissions.put("canView", true);
                permissions.put("isAdmin", true);
                permissions.put("isFacilitator", false);
                permissions.put("isControlOperator", false);
                permissions.put("isSoqmLead", false);
                permissions.put("isProcessOwner", false);
                permissions.put("isOnCurrentStep", false);
                return permissions;
            }
            
            // 1. Получаем текущий статус и шаг
            WorkflowStatus currentStatus = getCurrentWorkflowStatus(controlId);
            WorkflowStepDTO currentStep = getCurrentStep(controlId);

            // 2. Проверяем роли пользователя для этого контроля
            boolean isFacilitator = userRoles.contains("FACILITATOR");
            boolean isControlOperator = userRoles.contains("CONTROL_OPERATOR");
            boolean isSoqmLead = userRoles.contains("SOQM_LEAD");
            boolean isProcessOwner = userRoles.contains("PROCESS_OWNER");

            // 3. Проверяем назначен ли на текущий шаг
            boolean isOnCurrentStep = currentStep != null &&
                    userEmail.equals(currentStep.getAssignedToEmail());

            // 4. Edit control fields
            boolean canEdit = isFacilitator &&
                    (currentStatus == WorkflowStatus.NOT_STARTED ||
                            currentStatus == WorkflowStatus.FACILITATOR_REVIEW);
            permissions.put("canEdit", canEdit); // Boolean

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

            // 9. Дополнительная информация - тоже Boolean!
            permissions.put("isAdmin", false); // Boolean
            permissions.put("isFacilitator", isFacilitator); // Boolean
            permissions.put("isControlOperator", isControlOperator); // Boolean
            permissions.put("isSoqmLead", isSoqmLead); // Boolean
            permissions.put("isProcessOwner", isProcessOwner); // Boolean
            permissions.put("isOnCurrentStep", isOnCurrentStep); // Boolean

            if (currentStep != null) {
                permissions.put("currentStep", true);
            }

        } catch (Exception e) {
            log.error("Error calculating permissions: {}", e.getMessage(), e);
            // Возвращаем дефолтные значения при ошибке
            permissions.put("canEdit", false);
            permissions.put("canComment", false);
            permissions.put("canApprove", false);
            permissions.put("canReturn", false);
            permissions.put("canView", true);
        }

        return permissions;
    }

    // ★ НОВЫЙ МЕТОД: Проверка может ли пользователь возвращать с текущего шага
    private boolean canUserReturnFromStep(WorkflowStepType currentStepType,
                                          String userEmail,
                                          Long controlId) {
        switch (currentStepType) {
            case CONTROL_OPERATOR:
                // Control Operator может возвращать только если он действительно Control Operator
                return controlAssignmentService.isUserControlOperator(controlId, userEmail);

            case SOQM_LEAD:
                // SOQM Lead может возвращать только если он действительно SOQM Lead
                return controlAssignmentService.isUserSoqmLead(controlId, userEmail);

            case PROCESS_OWNER:
                // Process Owner может возвращать только если он действительно Process Owner
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

        boolean isFacilitator = controlAssignmentService.isUserFacilitator(controlId, userEmail);
        if (!isFacilitator) {
            return false;
        }

        WorkflowStatus currentStatus = getCurrentWorkflowStatus(controlId);

        boolean canEdit = (currentStatus == WorkflowStatus.NOT_STARTED
                || currentStatus == WorkflowStatus.FACILITATOR_REVIEW)
                && currentStatus != WorkflowStatus.RETURNED;

        log.debug("User {} can edit control {}: {} (status: {}, isFacilitator: {})",
                userEmail, controlId, canEdit, currentStatus, isFacilitator);

        return canEdit;
    }

    @Override
    public boolean isCurrentApprover(Long controlId, String userEmail) {
        Optional<WorkflowStep> currentStep = workflowStepRepository.findCurrentStep(controlId);
        return currentStep.filter(step -> userEmail.equals(step.getAssignedToEmail())).isPresent();
    }

    // Вспомогательные методы
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

    private void createHistoryRecord(Long controlId, String performerEmail,
                                     WorkflowActionType actionType,
                                     String fromStep, String toStep, String comments) {
        WorkflowHistory history = new WorkflowHistory();
        history.setControlId(controlId);
        history.setActionType(actionType);
        history.setPerformedByEmail(performerEmail);

        // Получаем и устанавливаем имя пользователя
        userService.getUserByEmail(performerEmail).ifPresent(user -> {
            history.setPerformedByName(user.getDisplayName());
        });

        history.setFromStep(fromStep);
        history.setToStep(toStep);
        history.setComments(comments);

        // createdAt устанавливается автоматически через @PrePersist

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