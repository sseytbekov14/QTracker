package com.kpmg.qtracker.entity;

import com.kpmg.qtracker.enums.CommentType;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "workflow_comments")
@Data
public class WorkflowComment {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "control_id")
    private Long controlId;

    @Column(name = "step_type")
    private String stepType; // FACILITATOR, CONTROL_OPERATOR, etc.

    @Column(name = "user_email")
    private String userEmail;

    @Column(name = "user_name")
    private String userName;

    @Column(length = 2000)
    private String comment;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "comment_type")
    private CommentType type;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}