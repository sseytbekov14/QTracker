package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.WorkflowButtonDTO;
import com.kpmg.qtracker.dto.WorkflowStepDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.WorkflowStatus;

import java.util.List;

public interface WorkflowService {

    // Инициализация workflow
    // initiatedBy is recorded as the performer of the INITIATE history entry
    void initiateWorkflow(Long controlId, String facilitatorEmail, User initiatedBy);

    // Получить текущий шаг workflow для контроля
    WorkflowStepDTO getCurrentStep(Long controlId);

    // Получить текущий статус workflow
    WorkflowStatus getCurrentWorkflowStatus(Long controlId);

    List<WorkflowStepDTO> getWorkflowSteps(Long controlId);

    // ★ ТОЛЬКО ОБЪЯВЛЕНИЕ метода, без реализации
    List<WorkflowButtonDTO> getAvailableButtons(Long controlId, String userEmail);

    /**
     * Check if control has reached specific workflow stage
     * @param controlId Control ID
     * @param stageName Stage name (e.g., "REVIEW")
     * @return true if control reached that stage
     */
    boolean hasReachedStage(Long controlId, String stageName);
}
