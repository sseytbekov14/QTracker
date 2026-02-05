package com.kpmg.qtracker.entity;

import com.kpmg.qtracker.enums.WorkflowActionType;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "workflow_history")
@Data
public class WorkflowHistory {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "control_id")
    private Long controlId;

    @Column(name = "action_type")
    @Enumerated(EnumType.STRING)
    private WorkflowActionType actionType;

    @Column(name = "performed_by_email")
    private String performedByEmail;

    @Column(name = "performed_by_name")
    private String performedByName;

    @Column(name = "from_step")
    private String fromStep;

    @Column(name = "to_step")
    private String toStep;

    @Column(length = 2000)
    private String comments;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = LocalDateTime.now();
    }
}