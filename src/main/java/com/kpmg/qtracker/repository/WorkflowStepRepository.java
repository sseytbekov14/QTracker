package com.kpmg.qtracker.repository;

import com.kpmg.qtracker.entity.WorkflowStep;
import com.kpmg.qtracker.enums.WorkflowStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface WorkflowStepRepository extends JpaRepository<WorkflowStep, Long> {

    // Найти шаги по controlId
    List<WorkflowStep> findByControlIdOrderBySequenceOrderAsc(Long controlId);

    // Найти текущий активный шаг
    @Query("SELECT ws FROM WorkflowStep ws WHERE ws.controlId = :controlId AND ws.status IN ('FACILITATOR_REVIEW', 'CONTROL_OPERATOR_REVIEW', 'SOQM_LEAD_REVIEW', 'PROCESS_OWNER_REVIEW')")
    Optional<WorkflowStep> findCurrentStep(@Param("controlId") Long controlId);

    // Найти шаги ожидающие апрува пользователя
    @Query("SELECT ws FROM WorkflowStep ws WHERE ws.assignedToEmail = :userEmail AND ws.status IN ('FACILITATOR_REVIEW', 'CONTROL_OPERATOR_REVIEW', 'SOQM_LEAD_REVIEW', 'PROCESS_OWNER_REVIEW')")
    List<WorkflowStep> findPendingStepsByUser(@Param("userEmail") String userEmail);

    // Найти шаг по controlId и типу шага
    Optional<WorkflowStep> findByControlIdAndStepType(Long controlId, String stepType);

    // Найти шаг по controlId и sequenceOrder
    Optional<WorkflowStep> findByControlIdAndSequenceOrder(Long controlId, Integer sequenceOrder);
}