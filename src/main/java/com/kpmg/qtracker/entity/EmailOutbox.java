package com.kpmg.qtracker.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * One e-mail of the outbox and its journal entry (V19): written with the event or by the scheduler, sent by
 * EmailOutboxDispatcher. Rows are inserted with SQL (OutboxWriter: ON CONFLICT DO NOTHING on dedupe_key); the entity
 * reads them.
 */
@Entity
@Table(name = "email_outbox",
        uniqueConstraints = @UniqueConstraint(name = "uq_email_outbox_dedupe_key", columnNames = "dedupe_key"),
        indexes = {
                @Index(name = "idx_email_outbox_status", columnList = "status,next_attempt_at"),
                @Index(name = "idx_email_outbox_control", columnList = "control_id,template_key")
        })
@Data
public class EmailOutbox {

    public static final String PENDING = "PENDING";
    public static final String SENDING = "SENDING";
    public static final String SENT = "SENT";
    public static final String FAILED = "FAILED";
    public static final String SKIPPED_DISABLED = "SKIPPED_DISABLED";
    public static final String SKIPPED_INACTIVE = "SKIPPED_INACTIVE";
    public static final String FAILED_RENDER = "FAILED_RENDER";

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "control_id")
    private Long controlId;

    @Column(name = "template_key", nullable = false, length = 10)
    private String templateKey;

    @Column(name = "recipient_user_id")
    private Long recipientUserId;

    @Column(name = "recipient_email", nullable = false, length = 320)
    private String recipientEmail;

    @Column(name = "subject", nullable = false, length = 500)
    private String subject;

    @Column(name = "body_html", nullable = false, columnDefinition = "TEXT")
    private String bodyHtml;

    @Column(name = "importance", nullable = false, length = 10)
    private String importance;

    @Column(name = "dedupe_key", nullable = false, length = 500)
    private String dedupeKey;

    @Column(name = "status", nullable = false, length = 20)
    private String status;

    @Column(name = "attempts", nullable = false)
    private Integer attempts = 0;

    @Column(name = "last_error", length = 2000)
    private String lastError;

    @Column(name = "provider", length = 20)
    private String provider;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    @Column(name = "claimed_at")
    private LocalDateTime claimedAt;
}
