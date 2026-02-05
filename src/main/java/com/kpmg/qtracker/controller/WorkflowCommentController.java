package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.dto.WorkflowCommentDTO;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.WorkflowCommentService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/workflow/comments")
@RequiredArgsConstructor
@Slf4j
public class WorkflowCommentController {

    private final WorkflowCommentService workflowCommentService;

    @GetMapping("/{controlId}")
    public ResponseEntity<List<WorkflowCommentDTO>> getComments(@PathVariable Long controlId) {
        try {
            List<WorkflowCommentDTO> comments = workflowCommentService.getComments(controlId);
            return ResponseEntity.ok(comments);
        } catch (Exception e) {
            log.error("Error getting comments: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/{controlId}")
    public ResponseEntity<?> addComment(@PathVariable Long controlId,
                                        @RequestBody WorkflowCommentDTO commentDTO,
                                        HttpSession session) {
        try {
            User currentUser = (User) session.getAttribute("currentUser");
            if (currentUser == null) {
                return ResponseEntity.status(401).body("User not authenticated");
            }

            commentDTO.setUserEmail(currentUser.getMail());
            commentDTO.setUserName(currentUser.getDisplayName());

            workflowCommentService.addComment(controlId, commentDTO);
            return ResponseEntity.ok().build();

        } catch (Exception e) {
            log.error("Error adding comment: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }
}