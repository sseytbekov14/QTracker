package com.kpmg.qtracker.dto;

import lombok.Data;

@Data
public class WorkflowButtonDTO {
    private String action;               // "SUBMIT_FOR_REVIEW", "RETURN_TO_FACILITATOR"
    private String label;                // "Submit for Review", "Return to Facilitator"
    private String cssClass;             // "btn-success", "btn-warning", "btn-info"
    private boolean requiresComment;     // Нужно ли вводить комментарий
    private String confirmationMessage;  // Сообщение подтверждения

    public WorkflowButtonDTO() {}

    public WorkflowButtonDTO(String action, String label, String cssClass,
                             boolean requiresComment, String confirmationMessage) {
        this.action = action;
        this.label = label;
        this.cssClass = cssClass;
        this.requiresComment = requiresComment;
        this.confirmationMessage = confirmationMessage;
    }
}