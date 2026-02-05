package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.dto.WorkflowButtonDTO;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.service.WorkflowService;
import jakarta.servlet.http.HttpSession;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/workflow")
@RequiredArgsConstructor
public class WorkflowApiController {

    private final ControlRepository controlRepository; // ← добавьте это
    private final WorkflowService workflowService;

    @GetMapping("/{controlId}/available-buttons")
    public ResponseEntity<List<WorkflowButtonDTO>> getAvailableButtons(
            @PathVariable Long controlId,
            HttpSession session) {

        User currentUser = (User) session.getAttribute("currentUser");
        if (currentUser == null) {
            return ResponseEntity.status(401).build();
        }

        List<WorkflowButtonDTO> buttons = workflowService.getAvailableButtons(
                controlId, currentUser.getMail());

        return ResponseEntity.ok(buttons);
    }
}