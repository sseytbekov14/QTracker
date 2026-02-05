package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.WorkflowActionDTO;
import com.kpmg.qtracker.dto.WorkflowButtonDTO;
import com.kpmg.qtracker.dto.WorkflowStepDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.enums.WorkflowStatus;

import java.util.List;
import java.util.Map;

public interface WorkflowService {

    // Инициализация workflow
    void initiateWorkflow(Long controlId, String facilitatorEmail);

    // Получить текущий шаг workflow для контроля
    WorkflowStepDTO getCurrentStep(Long controlId);

    // Получить текущий статус workflow
    WorkflowStatus getCurrentWorkflowStatus(Long controlId);

    Map<String, Boolean> getUserPermissions(Long controlId, String userEmail);

    List<WorkflowStepDTO> getWorkflowSteps(Long controlId);

    // Аппрув текущего шага
    WorkflowStepDTO approveStep(WorkflowActionDTO actionDTO, String approverEmail);

    // Возврат на доработку
    WorkflowStepDTO returnStep(WorkflowActionDTO actionDTO, String approverEmail);

    // Получить контроли ожидающие моего апрува
    List<Control> getPendingApprovals(String userEmail);

    // Проверить может ли пользователь редактировать контроль
    boolean canUserEditControl(Long controlId, String userEmail);

    // Проверить является ли пользователь текущим апрувером
    boolean isCurrentApprover(Long controlId, String userEmail);

    // ★ ТОЛЬКО ОБЪЯВЛЕНИЕ метода, без реализации
    List<WorkflowButtonDTO> getAvailableButtons(Long controlId, String userEmail);

    /**
     * Check if control has reached specific workflow stage
     * @param controlId Control ID
     * @param stageName Stage name (e.g., "Control Operator Review")
     * @return true if control reached that stage
     */
    boolean hasReachedStage(Long controlId, String stageName);
}