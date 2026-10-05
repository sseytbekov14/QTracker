package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.dto.PerformanceDTO;
import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.ControlPermission;
import com.kpmg.qtracker.service.ControlPermissionService;
import com.kpmg.qtracker.service.InitiationReadiness;
import com.kpmg.qtracker.service.ControlService;
import com.kpmg.qtracker.service.SoqmYear;
import com.kpmg.qtracker.service.ControlAssignmentService;
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
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/performance")
@RequiredArgsConstructor
@Slf4j
public class PerformanceController {
    private final ControlService controlService;
    private final ControlAssignmentService controlAssignmentService;
    private final WorkflowService workflowService;
    private final ControlPermissionService controlPermissionService;
    private final WorkflowTransitionGuard transitionGuard;

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
                    control, permission, WorkflowTransition.INITIATE);
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
