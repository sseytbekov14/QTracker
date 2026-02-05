package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.WorkflowCommentDTO;
import com.kpmg.qtracker.entity.WorkflowComment;
import com.kpmg.qtracker.enums.CommentType;
import com.kpmg.qtracker.repository.WorkflowCommentRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class WorkflowCommentServiceImpl implements WorkflowCommentService {

    private final WorkflowCommentRepository workflowCommentRepository;

    @Override
    public List<WorkflowCommentDTO> getComments(Long controlId) {
        return workflowCommentRepository.findByControlIdOrderByCreatedAtDesc(controlId)
                .stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    @Override
    public void addComment(Long controlId, WorkflowCommentDTO commentDTO) {
        WorkflowComment comment = new WorkflowComment();
        comment.setControlId(controlId);
        comment.setStepType(commentDTO.getStepType());
        comment.setUserEmail(commentDTO.getUserEmail());
        comment.setUserName(commentDTO.getUserName());
        comment.setComment(commentDTO.getComment());
        comment.setType(commentDTO.getType() != null ? commentDTO.getType() : CommentType.GENERAL_COMMENT);

        workflowCommentRepository.save(comment);
        log.info("Comment added for control: {}, by user: {}", controlId, commentDTO.getUserEmail());
    }

    @Override
    public List<WorkflowCommentDTO> getCommentsByStep(Long controlId, String stepType) {
        return workflowCommentRepository.findByControlIdAndStepTypeOrderByCreatedAtDesc(controlId, stepType)
                .stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    private WorkflowCommentDTO convertToDTO(WorkflowComment comment) {
        WorkflowCommentDTO dto = new WorkflowCommentDTO();
        dto.setId(comment.getId());
        dto.setControlId(comment.getControlId());
        dto.setStepType(comment.getStepType());
        dto.setUserEmail(comment.getUserEmail());
        dto.setUserName(comment.getUserName());
        dto.setComment(comment.getComment());
        dto.setCreatedAt(comment.getCreatedAt());
        dto.setType(comment.getType());
        return dto;
    }
}