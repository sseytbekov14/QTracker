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

    /** The role whose step the move was ("Facilitator", "SoQM Team", ...); null on rows before V9. */
    @Column(name = "acted_as", length = 40)
    private String actedAs;

    /** A SoQM user made the move for the participant who holds the step (performedBy is the SoQM user). */
    @Column(name = "on_behalf", nullable = false)
    private boolean onBehalf;

    /** The people assigned to that step when the move was made (e-mails, comma-separated). */
    @Column(name = "assigned_performer", length = 2000)
    private String assignedPerformer;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now(Notification.ZONE);
        }
    }
}