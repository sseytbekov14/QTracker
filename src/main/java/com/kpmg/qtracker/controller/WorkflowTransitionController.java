package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.IControlService;
import com.kpmg.qtracker.service.WorkflowMove;
import com.kpmg.qtracker.service.WorkflowMoveService;
import com.kpmg.qtracker.service.WorkflowTransition;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.bind.annotation.*;

import java.util.*;

@RestController
@RequestMapping("/api/workflow")
@RequiredArgsConstructor
public class WorkflowTransitionController {
    private final IControlService controlService;
    private final WorkflowMoveService workflowMoveService;

    @PostMapping("/submit-to-control-operator")
    @Transactional
    public ResponseEntity<?> submitToControlOperator(
            @RequestParam Long controlId,
            @RequestParam(required = false) String comments,
            HttpSession session) {
        return perform(controlId, session, List.of(WorkflowTransition.SUBMIT_TO_CONTROL_OPERATOR), comments,
                "Control submitted to Control Operator", "Error submitting control: ");
    }

    @PostMapping("/submit-to-soqm-lead")
    @Transactional
    public ResponseEntity<?> submitToSoqmLead(
            @RequestParam Long controlId,
            @RequestParam(required = false) String comments,
            HttpSession session) {
        return perform(controlId, session, List.of(WorkflowTransition.SUBMIT_TO_SOQM_TEAM), comments,
                "Control submitted to SoQM Team", "Error submitting control: ");
    }

    /**
     * Moves the control to a status: the user's own step, or as SoQM the step of any role, on to the next
     * status or back to any earlier one (business decision 3, {@link com.kpmg.qtracker.service.AccessPolicy#move}).
     */
    @PostMapping("/move")
    @Transactional
    public ResponseEntity<?> move(@RequestParam Long controlId,
                                  @RequestParam String targetStatus,
                                  @RequestParam(required = false) String comments,
                                  HttpSession session) {
        try {
            User currentUser = (User) session.getAttribute("currentUser");
            if (currentUser == null) {
                return ResponseEntity.status(401).body(Map.of("success", false, "message", "Unauthorized"));
            }
            Optional<Control> controlOpt = controlService.getControlById(controlId);
            if (controlOpt.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("success", false, "message", "Control not found"));
            }
            WorkflowMoveService.Outcome outcome =
                    workflowMoveService.moveTo(controlOpt.get(), currentUser, targetStatus, comments);
            return respond(outcome, outcome.ok()
                    ? "Control moved to " + WorkflowMove.displayStatus(outcome.newStatus()) : null);
        } catch (Exception e) {
            rollbackCurrentTransaction();
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "message", "Error moving control: " + e.getMessage()
            ));
        }
    }

    @PostMapping("/return-to-facilitator")
    @Transactional
    public ResponseEntity<?> returnToFacilitator(
            @RequestParam Long controlId,
            @RequestParam(required = false) String comments,
            HttpSession session) {
        return perform(controlId, session, List.of(WorkflowTransition.RETURN_TO_FACILITATOR), comments,
                "Control returned to Facilitator", "Error returning control: ");
    }

    /** One standard step through {@link WorkflowMoveService}, answered as these endpoints always answered. */
    private ResponseEntity<?> perform(Long controlId, HttpSession session, List<WorkflowTransition> candidates,
                                      String comments, String successMessage, String errorPrefix) {
        try {
            User currentUser = (User) session.getAttribute("currentUser");
            if (currentUser == null) {
                return ResponseEntity.status(401).body(Map.of("success", false, "message", "Unauthorized"));
            }
            Optional<Control> controlOpt = controlService.getControlById(controlId);
            if (controlOpt.isEmpty()) {
                return ResponseEntity.status(404).body(Map.of("success", false, "message", "Control not found"));
            }
            return respond(workflowMoveService.perform(controlOpt.get(), currentUser, candidates, comments),
                    successMessage);
        } catch (Exception e) {
            rollbackCurrentTransaction();
            return ResponseEntity.status(500).body(Map.of(
                    "success", false,
                    "message", errorPrefix + e.getMessage()
            ));
        }
    }

    private ResponseEntity<?> respond(WorkflowMoveService.Outcome outcome, String successMessage) {
        if (!outcome.ok()) {
            return ResponseEntity.status(outcome.httpStatus())
                    .body(Map.of("success", false, "message", outcome.message()));
        }
        Map<String, Object> response = new HashMap<>();
        response.put("success", true);
        response.put("message", successMessage);
        response.put("controlStatus", outcome.newStatus());
        response.put("onBehalf", outcome.move().onBehalf());
        return ResponseEntity.ok(response);
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
