package com.kpmg.qtracker.dto;

import com.kpmg.qtracker.enums.CommentType;
import lombok.Data;
import java.time.LocalDateTime;

@Data
public class WorkflowCommentDTO {
    private Long id;
    private Long controlId;
    private String stepType;
    private String userEmail;
    private String userName;
    private String comment;
    private LocalDateTime createdAt;
    private CommentType type;
}