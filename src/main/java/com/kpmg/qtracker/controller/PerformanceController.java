package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.dto.PerformanceDTO;
import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.ControlPermission;
import com.kpmg.qtracker.service.ControlPermissionService;
import com.kpmg.qtracker.service.ControlService;
import com.kpmg.qtracker.service.PerformanceService;
import com.kpmg.qtracker.service.ControlAssignmentService;
import com.kpmg.qtracker.service.UserService;
import com.kpmg.qtracker.service.WorkflowService;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Optional;

@RestController
@RequestMapping("/api/performance")
@RequiredArgsConstructor
@Slf4j
public class PerformanceController {
    private final PerformanceService performanceService;
    private final ControlService controlService;
    private final ControlAssignmentService controlAssignmentService;
    private final UserService userService;
    private final WorkflowService workflowService;
    private final ControlPermissionService controlPermissionService;

    @PostMapping("/auto-save")
    public ResponseEntity<?> autoSavePerformance(@RequestParam(required = false) String soqmYear,
                                                 @RequestParam(required = false) String actualOperationDate,
                                                 @RequestParam Long controlId) {
        try {
            Control control = controlService.getControlById(controlId)
                    .orElseThrow(() -> new RuntimeException("Control not found"));

            // Save soqmYear directly to controls table
            if (soqmYear != null && !soqmYear.trim().isEmpty()) {
                performanceService.saveSoqmYear(controlId, soqmYear);
            }

            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("Error in auto-save: {}", e.getMessage());
            return ResponseEntity.badRequest().body("Error auto-saving: " + e.getMessage());
        }
    }

    @GetMapping("/performance-cycle/{controlId}")
    public String performanceCycle(@PathVariable Long controlId, Model model, HttpSession session) {
        User currentUser = (User) session.getAttribute("currentUser");
        if (currentUser == null) {
            return "redirect:/login";
        }

        try {
            Control control = controlService.getControlById(controlId)
                    .orElseThrow(() -> new RuntimeException("Control not found"));

            ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(controlId);

            String processOwner = "Not assigned";
            if (assignment.getProcessOwner() != null && !assignment.getProcessOwner().isEmpty()) {
                String email = assignment.getProcessOwner().get(0);
                Optional<User> ownerUser = userService.getUserByEmail(email);
                processOwner = ownerUser.map(User::getDisplayName).orElse(email);
            }

            String facilitator = "Not assigned";
            if (assignment.getFacilitator() != null && !assignment.getFacilitator().isEmpty()) {
                String email = assignment.getFacilitator().get(0);
                Optional<User> facilitatorUser = userService.getUserByEmail(email);
                facilitator = facilitatorUser.map(User::getDisplayName).orElse(email);
            }

            String controlOperator = "Not assigned";
            if (assignment.getControlOperator() != null && !assignment.getControlOperator().isEmpty()) {
                String email = assignment.getControlOperator().get(0);
                Optional<User> operatorUser = userService.getUserByEmail(email);
                controlOperator = operatorUser.map(User::getDisplayName).orElse(email);
            }

            model.addAttribute("userName", currentUser.getDisplayName());
            model.addAttribute("userEmail", currentUser.getMail());
            model.addAttribute("controlId", control.getControlId());
            model.addAttribute("control", control);

            model.addAttribute("soqmYear", control.getSoqmYear());
            model.addAttribute("initiationDate", control.getCreatedAt() != null ? control.getCreatedAt().toLocalDate() : null);
            model.addAttribute("operationDate", assignment.getControlOperationDate());
            model.addAttribute("actualOperationDate", control.getCreatedAt() != null ? control.getCreatedAt().toLocalDate() : null);
            model.addAttribute("performanceStatus", control.getPerformanceStatus());
            model.addAttribute("facilitator", facilitator);
            model.addAttribute("controlOperator", controlOperator);
            model.addAttribute("processOwner", processOwner);
            model.addAttribute("lastUpdatedBy", currentUser.getDisplayName());
            model.addAttribute("lastUpdatedOn", LocalDateTime.now());

            return "performance-cycle";

        } catch (Exception e) {
            return "redirect:/performance/" + controlId + "?error=" + e.getMessage();
        }
    }

    @GetMapping("/{controlId}")
    public ResponseEntity<PerformanceDTO> getPerformance(@PathVariable Long controlId) {
        try {
            Control control = controlService.getControlById(controlId)
                    .orElseThrow(() -> new RuntimeException("Control not found"));

            PerformanceDTO performanceDTO = performanceService.buildPerformanceDTO(control);
            return ResponseEntity.ok(performanceDTO);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/initiate")
    public ResponseEntity<?> initiatePerformance(@ModelAttribute PerformanceDTO performanceDTO, HttpSession session) {
        try {
            User currentUser = (User) session.getAttribute("currentUser");
            if (currentUser == null) {
                return ResponseEntity.status(401).body("User not authenticated");
            }

            Control control = controlService.getControlById(performanceDTO.getControlId())
                    .orElseThrow(() -> new RuntimeException("Control not found"));

            ControlPermission permission = controlPermissionService.resolve(control, currentUser);
            if (!permission.canUseWorkflowActions()) {
                return ResponseEntity.status(403)
                        .body("Workflow actions are disabled for shared users on completed controls");
            }

            ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(control.getId());
            String facilitatorEmail = null;
            if (assignment != null && assignment.getFacilitator() != null && !assignment.getFacilitator().isEmpty()) {
                facilitatorEmail = assignment.getFacilitator().get(0);
            }

            if (facilitatorEmail == null) {
                return ResponseEntity.badRequest().body("Facilitator not assigned to this control");
            }

            // Save soqmYear to controls
            if (performanceDTO.getSoqmYear() != null && !performanceDTO.getSoqmYear().trim().isEmpty()) {
                control.setSoqmYear(performanceDTO.getSoqmYear());
            }

            control.setPerformanceStatus("IN_PROGRESS");
            controlService.save(control);

            workflowService.initiateWorkflow(control.getId(), facilitatorEmail);

            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error initiating: " + e.getMessage());
        }
    }
}
