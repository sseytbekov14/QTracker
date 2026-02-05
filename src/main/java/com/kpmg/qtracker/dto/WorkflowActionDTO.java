package com.kpmg.qtracker.dto;

import lombok.Data;

@Data
public class WorkflowActionDTO {
    private Long controlId;
    private String comments;
    private String returnReason;
    private String returnToStep; // На какой шаг вернуть (FACILITATOR, CONTROL_OPERATOR, SOQM_LEAD)
}