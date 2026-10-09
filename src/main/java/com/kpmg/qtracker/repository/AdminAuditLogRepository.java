package com.kpmg.qtracker.repository;

import com.kpmg.qtracker.entity.AdminAuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface AdminAuditLogRepository extends JpaRepository<AdminAuditLog, Long> {
    
    // Find all logs by admin email
    List<AdminAuditLog> findByAdminEmailOrderByCreatedAtDesc(String adminEmail);
    
    // Find all logs for a specific control
    List<AdminAuditLog> findByControlIdOrderByCreatedAtDesc(Long controlId);
    
    // Find logs by action type
    List<AdminAuditLog> findByActionTypeOrderByCreatedAtDesc(String actionType);
    
    // Find logs within date range
    List<AdminAuditLog> findByCreatedAtBetweenOrderByCreatedAtDesc(
        LocalDateTime startDate, 
        LocalDateTime endDate
    );
    
    // Admin Panel Audit Trail: the latest entries of one event type (AdminAuditTrail)
    List<AdminAuditLog> findTop100ByActionTypeStartingWithOrderByCreatedAtDesc(String actionTypePrefix);

    List<AdminAuditLog> findTop100ByActionTypeOrderByCreatedAtDesc(String actionType);

    @Query("select l from AdminAuditLog l where l.actionType not like 'USER%' and l.actionType <> 'EDIT'"
            + " and l.actionType not like 'ATTACHMENT%' order by l.createdAt desc")
    List<AdminAuditLog> findOtherActionTypes(Pageable pageable);
}
