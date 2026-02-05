package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.AdminAuditLog;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.repository.AdminAuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class AdminAuditService {
    
    private final AdminAuditLogRepository auditLogRepository;
    
    /**
     * Log an ADMIN action
     * @param adminEmail Email of the admin user
     * @param adminName Display name of the admin user
     * @param actionType Type of action (VIEW, EDIT, DELETE, APPROVE, RETURN, etc.)
     * @param control The control being acted upon
     * @param description Description of the action
     */
    public void logAction(String adminEmail, String adminName, String actionType, 
                         Control control, String description) {
        try {
            AdminAuditLog auditLog = new AdminAuditLog();
            auditLog.setAdminEmail(adminEmail);
            auditLog.setAdminName(adminName);
            auditLog.setActionType(actionType);
            
            if (control != null) {
                auditLog.setControlId(control.getId());
                auditLog.setControlControlId(control.getControlId());
            }
            auditLog.setActionDescription(description);
            auditLogRepository.save(auditLog);
            
            log.info("ADMIN ACTION LOGGED: {} by {} on control {}", 
                    actionType, adminEmail, control != null ? control.getControlId() : "N/A");
        } catch (Exception e) {
            log.error("Failed to log admin action: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Log an ADMIN action with field changes
     */
    public void logActionWithChanges(String adminEmail, String adminName, String actionType,
                                     Control control, String description,
                                     String changedFields, String previousValues, String newValues) {
        try {
            AdminAuditLog auditLog = new AdminAuditLog();
            auditLog.setAdminEmail(adminEmail);
            auditLog.setAdminName(adminName);
            auditLog.setActionType(actionType);
            
            if (control != null) {
                auditLog.setControlId(control.getId());
                auditLog.setControlControlId(control.getControlId());
            }
            
            auditLog.setActionDescription(description);
            auditLog.setChangedFields(changedFields);
            auditLog.setPreviousValues(previousValues);
            auditLog.setNewValues(newValues);
            auditLogRepository.save(auditLog);
            
            log.info("ADMIN EDIT LOGGED: {} changed {} on control {}", 
                    adminEmail, changedFields, control != null ? control.getControlId() : "N/A");
        } catch (Exception e) {
            log.error("Failed to log admin action with changes: {}", e.getMessage(), e);
        }
    }
    
    /**
     * Get all audit logs for a specific admin
     */
    public List<AdminAuditLog> getLogsByAdmin(String adminEmail) {
        return auditLogRepository.findByAdminEmailOrderByCreatedAtDesc(adminEmail);
    }
    
    /**
     * Get all audit logs for a specific control
     */
    public List<AdminAuditLog> getLogsByControl(Long controlId) {
        return auditLogRepository.findByControlIdOrderByCreatedAtDesc(controlId);
    }
    
    /**
     * Get recent audit logs
     */
    public List<AdminAuditLog> getRecentLogs() {
        return auditLogRepository.findTop100ByOrderByCreatedAtDesc();
    }
    
    /**
     * Get logs by action type
     */
    public List<AdminAuditLog> getLogsByActionType(String actionType) {
        return auditLogRepository.findByActionTypeOrderByCreatedAtDesc(actionType);
    }
    
    /**
     * Get logs within a date range
     */
    public List<AdminAuditLog> getLogsByDateRange(LocalDateTime startDate, LocalDateTime endDate) {
        return auditLogRepository.findByCreatedAtBetweenOrderByCreatedAtDesc(startDate, endDate);
    }
}
