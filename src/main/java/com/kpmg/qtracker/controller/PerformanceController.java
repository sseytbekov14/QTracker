package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.dto.PerformanceDTO;
import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.ControlPermission;
import com.kpmg.qtracker.service.ControlPermissionService;
import com.kpmg.qtracker.service.InitiationReadiness;
import com.kpmg.qtracker.service.ControlService;
import com.kpmg.qtracker.service.PerformanceService;
import com.kpmg.qtracker.service.SoqmYear;
import com.kpmg.qtracker.service.ControlAssignmentService;
import com.kpmg.qtracker.service.UserService;
import com.kpmg.qtracker.service.WorkflowService;
import com.kpmg.qtracker.service.WorkflowTransition;
import com.kpmg.qtracker.service.WorkflowTransitionGuard;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;
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
    private final WorkflowTransitionGuard transitionGuard;

    @PostMapping("/auto-save")
    public ResponseEntity<?> autoSavePerformance(@RequestParam(required = false) String soqmYear,
                                                 @RequestParam(required = false) String actualOperationDate,
                                                 @RequestParam Long controlId,
                                                 HttpSession session) {
        try {
            User currentUser = (User) session.getAttribute("currentUser");
            if (currentUser == null) {
                return ResponseEntity.status(401).body("User not authenticated");
            }
            Control control = controlService.getControlById(controlId)
                    .orElseThrow(() -> new RuntimeException("Control not found"));
            // Same circle as Initiate: the SoQM year is set while preparing the control for its cycle
            if (!controlPermissionService.resolve(control, currentUser).canEditAll() && !isCreator(control, currentUser)) {
                return ResponseEntity.status(403).body("Only SoQM Team or the control creator can change the SoQM year");
            }

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
            model.addAttribute("lastUpdatedOn", LocalDateTime.now(Notification.ZONE));

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
    @Transactional
    public ResponseEntity<?> initiatePerformance(@ModelAttribute PerformanceDTO performanceDTO, HttpSession session) {
        try {
            User currentUser = (User) session.getAttribute("currentUser");
            if (currentUser == null) {
                return ResponseEntity.status(401).body("User not authenticated");
            }

            Control control = controlService.getControlById(performanceDTO.getControlId())
                    .orElseThrow(() -> new RuntimeException("Control not found"));

            ControlPermission permission = controlPermissionService.resolve(control, currentUser);
            WorkflowTransitionGuard.Decision decision = transitionGuard.check(
                    control, currentUser, permission, WorkflowTransition.INITIATE);
            if (!decision.allowed()) {
                return ResponseEntity.status(decision.httpStatus()).body(decision.message());
            }

            ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(control.getId());
            // The SoQM Year chosen in the Initiate confirmation, else the one already stored
            String soqmYear = performanceDTO.getSoqmYear() == null || performanceDTO.getSoqmYear().isBlank()
                    ? null : performanceDTO.getSoqmYear().trim();
            List<String> missing = InitiationReadiness.missing(control, assignment,
                    soqmYear != null ? soqmYear : control.getSoqmYear());
            if (!missing.isEmpty()) {
                return ResponseEntity.badRequest().body("Required fields are missing: " + String.join(", ", missing));
            }
            if (soqmYear != null && !SoqmYear.isValid(soqmYear)) {
                return ResponseEntity.badRequest().body(SoqmYear.invalidMessage());
            }
            if (soqmYear != null) {
                control.setSoqmYear(soqmYear);
            }
            String facilitatorEmail = assignment.getFacilitator().stream()
                    .filter(email -> email != null && !email.isBlank())
                    .findFirst()
                    .orElseThrow();

            control.setPerformanceStatus("IN_PROGRESS");
            controlService.save(control);

            workflowService.initiateWorkflow(control.getId(), facilitatorEmail, currentUser);

            return ResponseEntity.ok().build();
        } catch (Exception e) {
            rollbackCurrentTransaction();
            return ResponseEntity.badRequest().body("Error initiating: " + e.getMessage());
        }
    }

    private boolean isCreator(Control control, User user) {
        return user.getMail() != null && control.getCreatedBy() != null && control.getCreatedBy().getMail() != null
                && control.getCreatedBy().getMail().trim().equalsIgnoreCase(user.getMail().trim());
    }

    /**
     * Errors are turned into responses instead of propagating, so the transaction would commit a
     * half-done transition (status without history or workflow steps); mark it for rollback instead.
     */
    private void rollbackCurrentTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
    }
}
