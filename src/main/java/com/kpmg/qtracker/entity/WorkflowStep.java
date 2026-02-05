package com.kpmg.qtracker.entity;

import com.kpmg.qtracker.enums.WorkflowStepType;
import com.kpmg.qtracker.enums.WorkflowStatus;
import jakarta.persistence.*;
import lombok.Data;
import java.time.LocalDateTime;

@Entity
@Table(name = "workflow_steps")
@Data
public class WorkflowStep {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "control_id")
    private Long controlId;

    @Enumerated(EnumType.STRING)
    @Column(name = "step_type")
    private WorkflowStepType stepType;

    @Column(name = "assigned_to_email")
    private String assignedToEmail;

    @Column(name = "assigned_to_name")
    private String assignedToName;

    @Enumerated(EnumType.STRING)
    private WorkflowStatus status;

    @Column(name = "assigned_at")
    private LocalDateTime assignedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "sequence_order")
    private Integer sequenceOrder;

    @Column(length = 2000)
    private String comments;

    @Column(name = "return_reason")
    private String returnReason;

    @Column(name = "returned_to_step")
    private String returnedToStep;

    @PrePersist
    protected void onCreate() {
        assignedAt = LocalDateTime.now();
    }
}