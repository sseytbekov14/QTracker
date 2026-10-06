package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.entity.*;
import com.kpmg.qtracker.service.*;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/workflow")
@RequiredArgsConstructor
@Slf4j
public class WorkflowController {

    private final ControlService controlService;
    private final WorkflowMoveService workflowMoveService;

    /** Every return needs a reason (spec 9.4). */
    static final String RETURN_COMMENT_REQUIRED = WorkflowMoveService.RETURN_COMMENT_REQUIRED;

    @PostMapping("/perform-action")
    @Transactional
    public ResponseEntity<?> performWorkflowAction(@Valid @RequestBody WorkflowActionRequest request,
                                                   HttpSession session) {
        try {
            User currentUser = (User) session.getAttribute("currentUser");
            if (currentUser == null) {
                return ResponseEntity.status(401).body("User not authenticated");
            }

            Control control = controlService.getControlById(request.getControlId())
                    .orElseThrow(() -> new RuntimeException("Control not found"));
            List<WorkflowTransition> candidates = WorkflowTransition.forAction(request.getAction());
            if (candidates.isEmpty()) {
                return ResponseEntity.badRequest().body("Unsupported workflow action: " + request.getAction());
            }
            log.info("Workflow action: {} for control: {} by user: {}",
                    request.getAction(), request.getControlId(), currentUser.getMail());
            WorkflowMoveService.Outcome outcome =
                    workflowMoveService.perform(control, currentUser, candidates, request.getComment());
            if (!outcome.ok()) {
                return ResponseEntity.status(outcome.httpStatus()).body(outcome.message());
            }
            return ResponseEntity.ok().build();

        } catch (Exception e) {
            log.error("Error performing workflow action: {}", e.getMessage(), e);
            rollbackCurrentTransaction();
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    // ========== SOQM TEAM WORKFLOW ENDPOINTS ==========
    
    @PostMapping("/submit-to-process-owner")
    @Transactional
    public ResponseEntity<?> submitToProcessOwner(@RequestParam Long controlId,
                                                  @RequestParam(required = false) String comments,
                                                  HttpSession session) {
        return perform(controlId, session, List.of(WorkflowTransition.SUBMIT_TO_PROCESS_OWNER), comments,
                "Control submitted to Process Owner");
    }

    /**
     * Back to the Control Operator (spec 9.4): by SoQM from SoQM review, by the Process Owner (or SoQM for
     * them) from Process Owner review. A comment is required; it is stored on the control, in the history
     * (RETURN_TO_OPERATOR) and sent to the Control Operator.
     */
    @PostMapping("/return-to-operator")
    @Transactional
    public ResponseEntity<?> returnToOperator(@RequestParam Long controlId,
                                              @RequestParam(required = false) String comments,
                                              HttpSession session) {
        return perform(controlId, session,
                List.of(WorkflowTransition.RETURN_TO_OPERATOR, WorkflowTransition.OWNER_RETURN_TO_OPERATOR), comments,
                "Control returned to Control Operator");
    }

    @PostMapping("/complete-control")
    @Transactional
    public ResponseEntity<?> completeControl(@RequestParam Long controlId,
                                             @RequestParam(required = false) String comments,
                                             HttpSession session) {
        return perform(controlId, session, List.of(WorkflowTransition.COMPLETE), comments, "Control completed");
    }

    /** One standard step through {@link WorkflowMoveService}, answered as these endpoints always answered. */
    private ResponseEntity<?> perform(Long controlId, HttpSession session, List<WorkflowTransition> candidates,
                                      String comments, String successMessage) {
        try {
            User currentUser = (User) session.getAttribute("currentUser");
            if (currentUser == null) {
                return ResponseEntity.status(401).body("User not authenticated");
            }
            Optional<Control> controlOpt = controlService.getControlById(controlId);
            if (controlOpt.isEmpty()) {
                return ResponseEntity.badRequest().body("Control not found");
            }
            WorkflowMoveService.Outcome outcome =
                    workflowMoveService.perform(controlOpt.get(), currentUser, candidates, comments);
            if (!outcome.ok()) {
                return ResponseEntity.status(outcome.httpStatus()).body(outcome.message());
            }
            return ResponseEntity.ok(successMessage);
        } catch (Exception e) {
            log.error("Error in workflow step on control {}: {}", controlId, e.getMessage(), e);
            rollbackCurrentTransaction();
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @Data
    private static class WorkflowActionRequest {
        private Long controlId;
        private String action;
        @Size(max = 2000, message = "Comment must be at most 2000 characters")
        private String comment;
    }

    /**
     * Errors are turned into responses instead of propagating, so the transaction would commit a
     * half-done transition (status without history); mark it for rollback instead.
     */
    private void rollbackCurrentTransaction() {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
        }
    }
}
