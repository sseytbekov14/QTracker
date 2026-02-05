package com.kpmg.qtracker.entity;

import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "notification_reads", 
       uniqueConstraints = @UniqueConstraint(columnNames = {"user_id", "notification_id"}))
@Data
public class NotificationRead {
    
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    
    @Column(name = "user_id", nullable = false)
    private Long userId;
    
    @Column(name = "notification_id", nullable = false, length = 36)
    private String notificationId;  // UUID of the notification
    
    @Column(name = "read_at", nullable = false)
    private LocalDateTime readAt;
}
