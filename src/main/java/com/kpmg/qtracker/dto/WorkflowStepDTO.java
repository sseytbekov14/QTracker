package com.kpmg.qtracker.dto;

import com.kpmg.qtracker.enums.WorkflowStatus;
import com.kpmg.qtracker.enums.WorkflowStepType;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class WorkflowStepDTO {
    private Long id;
    private Long controlId;
    private WorkflowStepType stepType;
    private String assignedToEmail;
    private String assignedToName;
    private WorkflowStatus status;
    private LocalDateTime assignedAt;
    private LocalDateTime completedAt;
    private Integer sequenceOrder;
    private String comments;
    private String returnReason;
    private String returnedToStep;
}