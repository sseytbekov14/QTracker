package com.kpmg.qtracker.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "admin_audit_log")
@Data
public class AdminAuditLog {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "admin_email", nullable = false)
    private String adminEmail;

    @Column(name = "admin_name")
    private String adminName;

    @Column(name = "action_type", nullable = false, length = 50)
    private String actionType;  // VIEW, EDIT, DELETE, APPROVE, RETURN, etc.

    @Column(name = "control_id")
    private Long controlId;

    @Column(name = "control_control_id", length = 100)
    private String controlControlId;  // Business ID like CTRL-001

    @Column(name = "action_description", columnDefinition = "TEXT")
    private String actionDescription;

    @Column(name = "changed_fields", columnDefinition = "TEXT")
    private String changedFields;  // JSON or CSV

    @Column(name = "previous_values", columnDefinition = "TEXT")
    private String previousValues;  // JSON

    @Column(name = "new_values", columnDefinition = "TEXT")
    private String newValues;  // JSON

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "user_agent", length = 500)
    private String userAgent;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
