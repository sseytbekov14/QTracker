package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.WorkflowCommentDTO;

import java.util.List;

public interface WorkflowCommentService {

    List<WorkflowCommentDTO> getComments(Long controlId);

    void addComment(Long controlId, WorkflowCommentDTO commentDTO);

    List<WorkflowCommentDTO> getCommentsByStep(Long controlId, String stepType);
}