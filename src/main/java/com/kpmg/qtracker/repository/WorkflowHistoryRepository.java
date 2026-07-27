package com.kpmg.qtracker.repository;

import com.kpmg.qtracker.entity.WorkflowHistory;
import com.kpmg.qtracker.enums.WorkflowActionType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkflowHistoryRepository extends JpaRepository<WorkflowHistory, Long> { // ★ ИЗМЕНИТЬ

    List<WorkflowHistory> findByControlIdOrderByCreatedAtDesc(Long controlId); // ★ ИЗМЕНИТЬ

    List<WorkflowHistory> findByControlIdAndPerformedByEmailOrderByCreatedAtDesc( // ★ ИЗМЕНИТЬ имя поля
                                                                                  Long controlId, String performedByEmail); // ★ ИЗМЕНИТЬ параметр
    @Query("SELECT h FROM WorkflowHistory h WHERE h.controlId = :controlId " + // ★ ИЗМЕНИТЬ
            "ORDER BY h.createdAt DESC")
    List<WorkflowHistory> findWorkflowHistory(@Param("controlId") Long controlId); // ★ ИЗМЕНИТЬ тип

    List<WorkflowHistory> findByActionTypeOrderByCreatedAtDesc(WorkflowActionType actionType); // ★ ИЗМЕНИТЬ тип

    // Check if control reached specific workflow stage
    @Query("SELECT CASE WHEN COUNT(h) > 0 THEN true ELSE false END " +
           "FROM WorkflowHistory h WHERE h.controlId = :controlId " +
           "AND (h.toStep = :stageName OR h.fromStep = :stageName)")
    boolean hasReachedStage(@Param("controlId") Long controlId, @Param("stageName") String stageName);

    @Query("SELECT CASE WHEN COUNT(h) > 0 THEN true ELSE false END " +
           "FROM WorkflowHistory h WHERE h.controlId = :controlId " +
           "AND h.performedByEmail = :email " +
           "AND h.fromStep = 'COMPLETED' " +
           "AND h.actionType = com.kpmg.qtracker.enums.WorkflowActionType.SUBMIT_TO_SOQM_TEAM")
    boolean hasSharedSubmitted(@Param("controlId") Long controlId, @Param("email") String email);

    @Query("SELECT h.controlId, MAX(h.createdAt) " +
           "FROM WorkflowHistory h " +
           "WHERE h.controlId IN :controlIds " +
           "AND ((h.toStep IS NOT NULL AND UPPER(TRIM(h.toStep)) = 'COMPLETED') " +
           "OR (h.actionType = com.kpmg.qtracker.enums.WorkflowActionType.APPROVE " +
           "AND h.fromStep IS NOT NULL " +
           "AND UPPER(TRIM(h.fromStep)) = 'PROCESS_OWNER_REVIEW')) " +
           "GROUP BY h.controlId")
    List<Object[]> findLatestCompletionTimestampByControlIds(@Param("controlIds") List<Long> controlIds);
}
