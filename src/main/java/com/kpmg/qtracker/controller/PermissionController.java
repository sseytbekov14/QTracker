package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.dto.UserDTO;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.WorkflowService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

@RestController
@RequestMapping("/api/permissions")
@RequiredArgsConstructor
@Slf4j
public class PermissionController {

    private final WorkflowService workflowService;

    @GetMapping("/{controlId}")
    public ResponseEntity<Map<String, Object>> getPermissions(
            @PathVariable Long controlId,
            HttpSession session) {

        try {
            User currentUser = (User) session.getAttribute("currentUser");
            if (currentUser == null) {
                return ResponseEntity.status(401).build();
            }

            // Получаем права через WorkflowService
            Map<String, Boolean> permissions = workflowService.getUserPermissions(
                    controlId, currentUser.getMail());

            // Создаем response
            Map<String, Object> response = new HashMap<>();
            response.put("controlId", controlId);
            response.put("userEmail", currentUser.getMail());
            response.put("userName", currentUser.getDisplayName());
            response.put("permissions", permissions);

            log.info("Permissions for control {}: user={}, permissions={}",
                    controlId, currentUser.getMail(), permissions);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error getting permissions for control {}: {}", controlId, e.getMessage(), e);
            return ResponseEntity.badRequest().build();
        }
    }

    @GetMapping("/{controlId}/can-edit")
    public ResponseEntity<Map<String, Boolean>> canEditControl(
            @PathVariable Long controlId,
            HttpSession session) {

        try {
            User currentUser = (User) session.getAttribute("currentUser");
            if (currentUser == null) {
                return ResponseEntity.status(401).build();
            }

            boolean canEdit = workflowService.canUserEditControl(
                    controlId, currentUser.getMail());

            Map<String, Boolean> response = new HashMap<>();
            response.put("canEdit", canEdit);

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Error checking edit permission: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().build();
        }
    }
}