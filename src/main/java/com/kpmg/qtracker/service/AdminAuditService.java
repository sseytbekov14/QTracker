package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.AdminAuditLog;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.repository.AdminAuditLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;


@Service
@RequiredArgsConstructor
@Slf4j
public class AdminAuditService {
    
    private final AdminAuditLogRepository auditLogRepository;
    
    /**
     * Log an ADMIN action with field changes. Returns the saved entry, or null when it could not be saved
     * (the action itself is not undone).
     */
    public AdminAuditLog logActionWithChanges(String adminEmail, String adminName, String actionType,
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
            AdminAuditLog saved = auditLogRepository.save(auditLog);
            
            log.info("ADMIN EDIT LOGGED: {} changed {} on control {}", 
                    adminEmail, changedFields, control != null ? control.getControlId() : "N/A");
            return saved;
        } catch (Exception e) {
            log.error("Failed to log admin action with changes: {}", e.getMessage(), e);
            return null;
        }
    }
}
