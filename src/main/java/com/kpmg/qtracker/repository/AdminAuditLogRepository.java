package com.kpmg.qtracker.repository;

import com.kpmg.qtracker.entity.AdminAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;
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
    
    // Find recent logs (last N records)
    List<AdminAuditLog> findTop100ByOrderByCreatedAtDesc();
}
