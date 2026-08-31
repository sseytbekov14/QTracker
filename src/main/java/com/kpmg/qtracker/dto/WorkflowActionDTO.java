package com.kpmg.qtracker.dto;

import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class WorkflowActionDTO {
    private Long controlId;
    @Size(max = 2000, message = "Comments must be at most 2000 characters")
    private String comments;
    @Size(max = 2000, message = "Return reason must be at most 2000 characters")
    private String returnReason;
    @Size(max = 255, message = "Return to step must be at most 255 characters")
    private String returnToStep; // На какой шаг вернуть (FACILITATOR, CONTROL_OPERATOR, SOQM_TEAM)
}