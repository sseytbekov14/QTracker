package com.kpmg.qtracker.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Data;

import java.time.LocalDateTime;

/** The subject and body of one notification, T1..T12 (V19, filled by V20); edited in the database, no screen. */
@Entity
@Table(name = "notification_template")
@Data
public class NotificationTemplateText {

    @Id
    @Column(name = "template_key", length = 10)
    private String templateKey;

    @Column(name = "subject", nullable = false, length = 500)
    private String subject;

    @Column(name = "body", nullable = false, columnDefinition = "TEXT")
    private String body;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;
}
