package com.kpmg.qtracker.repository;

import com.kpmg.qtracker.entity.WorkflowComment;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface WorkflowCommentRepository extends JpaRepository<WorkflowComment, Long> {

    // Найти комментарии по controlId
    List<WorkflowComment> findByControlIdOrderByCreatedAtDesc(Long controlId);

    // Найти комментарии по controlId и типу шага
    List<WorkflowComment> findByControlIdAndStepTypeOrderByCreatedAtDesc(Long controlId, String stepType);

    // Найти последние N комментариев
    @Query("SELECT wc FROM WorkflowComment wc WHERE wc.controlId = :controlId ORDER BY wc.createdAt DESC LIMIT :limit")
    List<WorkflowComment> findRecentComments(@Param("controlId") Long controlId, @Param("limit") int limit);
}