package com.kpmg.qtracker.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "notifications", indexes = {
    @Index(name = "idx_user_created", columnList = "user_id,created_at"),
    @Index(name = "idx_control", columnList = "control_id")
})
@Data
public class Notification {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "user_id", nullable = false)
    private Long userId;  // recipient
    
    @Column(name = "control_id", nullable = false)
    private Long controlId;
    
    @Column(name = "type", nullable = false, length = 50)
    private String type;  // INITIATE, STATUS_CHANGE, COMMENT, FILE_UPLOAD, WORKFLOW_STEP
    
    @Column(name = "title", nullable = false)
    private String title;
    
    @Column(name = "message", nullable = false, columnDefinition = "TEXT")
    private String message;
    
    @Column(name = "is_read", nullable = false)
    private Boolean isRead = false;
    
    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;
    
    @Column(name = "read_at")
    private LocalDateTime readAt;
    
    @Column(name = "link")
    private String link;  // URL to control or detail page
    
    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (isRead == null) {
            isRead = false;
        }
    }
}
