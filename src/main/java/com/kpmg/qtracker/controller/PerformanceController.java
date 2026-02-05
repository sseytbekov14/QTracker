package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.dto.PerformanceDTO;
import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlPerformance;
import com.kpmg.qtracker.entity.User;
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

    @PostMapping("/auto-save")
    public ResponseEntity<?> autoSavePerformance(@RequestParam(required = false) String soqmYear,
                                                 @RequestParam(required = false) String actualOperationDate,
                                                 @RequestParam Long controlId) {
        try {
            System.out.println("=== AUTO-SAVE PERFORMANCE ===");
            System.out.println("Control ID: " + controlId);
            System.out.println("SoQM Year: " + soqmYear);
            System.out.println("Actual Date from request: '" + actualOperationDate + "'");

            Control control = controlService.getControlById(controlId)
                    .orElseThrow(() -> new RuntimeException("Control not found"));

            PerformanceDTO performanceDTO = new PerformanceDTO();
            performanceDTO.setControlId(controlId);
            performanceDTO.setSoqmYear(soqmYear);

            if (actualOperationDate != null && !actualOperationDate.trim().isEmpty()) {
                System.out.println("Attempting to parse date: '" + actualOperationDate + "'");
                try {
                    performanceDTO.setActualOperationDate(LocalDate.parse(actualOperationDate));
                    System.out.println("Parsed as ISO format: " + performanceDTO.getActualOperationDate());
                } catch (DateTimeParseException e) {
                    log.warn("Rejected non-ISO actualOperationDate '{}'. Expected yyyy-MM-dd", actualOperationDate);
                    throw new RuntimeException("Invalid date format: " + actualOperationDate + ". Expected: yyyy-MM-dd");
                }
            } else {
                System.out.println("⚠️ No actualOperationDate provided in request");
            }

            // ★★★ ИСПРАВЛЕННАЯ ЛОГИКА СТАТУСА - use control_status now
            String currentStatus = control.getControlStatus();
            if (currentStatus == null || currentStatus.isEmpty()) {
                currentStatus = "In Progress";
            }
            System.out.println("📊 Current control status: " + currentStatus);

            boolean hasNewData = (soqmYear != null && !soqmYear.trim().isEmpty()) ||
                    (actualOperationDate != null && !actualOperationDate.trim().isEmpty());

            // If status is "In Progress" and has new data, keep it as "In Progress"
            if ("In Progress".equals(currentStatus) && hasNewData) {
                System.out.println("✅ Status remains: In Progress");
            }

            System.out.println("📊 Final status to save: " + currentStatus);

            // Update control_status instead of performance_status
            if (currentStatus != null && !currentStatus.isEmpty()) {
                control.setControlStatus(currentStatus);
                controlService.save(control);
            }

            performanceService.savePerformance(performanceDTO, control);

            System.out.println("✅ Auto-save successful for control ID: " + controlId);
            System.out.println("📊 Saved control status: " + control.getControlStatus());
            System.out.println("=== END AUTO-SAVE ===");

            return ResponseEntity.ok().build();
        } catch (Exception e) {
            System.out.println("❌ Error in auto-save: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.badRequest().body("Error auto-saving: " + e.getMessage());
        }
    }

    @GetMapping("/performance-cycle/{controlId}")
    public String performanceCycle(@PathVariable Long controlId, Model model, HttpSession session) {
        // Проверка авторизации
        User currentUser = (User) session.getAttribute("currentUser");
        if (currentUser == null) {
            return "redirect:/login";
        }

        try {
            // 1. Получаем Control
            Control control = controlService.getControlById(controlId)
                    .orElseThrow(() -> new RuntimeException("Control not found"));

            // 2. Получаем Performance (после инициализации)
            ControlPerformance performance = performanceService.findByControlId(controlId)
                    .orElseThrow(() -> new RuntimeException("Performance not initialized"));

            // 3. Получаем Assignment
            ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(controlId);

            // 4. Получаем Process Owner из Assignment
            String processOwner = "Not assigned";
            if (assignment.getProcessOwner() != null && !assignment.getProcessOwner().isEmpty()) {
                List<String> processOwners = assignment.getProcessOwner();
                // Берем первого process owner
                String email = processOwners.get(0);
                Optional<User> ownerUser = userService.getUserByEmail(email);
                processOwner = ownerUser.map(User::getDisplayName).orElse(email);
            }

            // 5. Получаем Facilitator из Assignment
            String facilitator = "Not assigned";
            if (assignment.getFacilitator() != null && !assignment.getFacilitator().isEmpty()) {
                List<String> facilitators = assignment.getFacilitator();
                String email = facilitators.get(0);
                Optional<User> facilitatorUser = userService.getUserByEmail(email);
                facilitator = facilitatorUser.map(User::getDisplayName).orElse(email);
            }

            // 6. Получаем Control Operator из Assignment
            String controlOperator = "Not assigned";
            if (assignment.getControlOperator() != null && !assignment.getControlOperator().isEmpty()) {
                List<String> operators = assignment.getControlOperator();
                String email = operators.get(0);
                Optional<User> operatorUser = userService.getUserByEmail(email);
                controlOperator = operatorUser.map(User::getDisplayName).orElse(email);
            }

            model.addAttribute("userName", currentUser.getDisplayName());
            model.addAttribute("userEmail", currentUser.getMail());
            model.addAttribute("controlId", control.getControlId());
            model.addAttribute("control", control);

            model.addAttribute("soqmYear", performance.getSoqmYear());
            model.addAttribute("initiationDate", performance.getCreatedAt()); // Дата создания performance
            model.addAttribute("operationDate", assignment.getControlOperationDate());
            model.addAttribute("actualOperationDate", performance.getActualOperationDate());
            model.addAttribute("performanceStatus", control.getControlStatus()); // Use control_status
            model.addAttribute("facilitator", facilitator);
            model.addAttribute("controlOperator", controlOperator);
            model.addAttribute("processOwner", processOwner);
            model.addAttribute("lastUpdatedBy", currentUser.getDisplayName());
            model.addAttribute("lastUpdatedOn", LocalDateTime.now());

            return "performance-cycle";

        } catch (Exception e) {
            // В случае ошибки возвращаемся на страницу performance
            return "redirect:/performance/" + controlId + "?error=" + e.getMessage();
        }
    }

    @GetMapping("/{controlId}")
    public ResponseEntity<PerformanceDTO> getPerformance(@PathVariable Long controlId) {
        try {
            Control control = controlService.getControlById(controlId)
                    .orElseThrow(() -> new RuntimeException("Control not found"));

            Optional<ControlPerformance> performance = performanceService.findByControlId(controlId);

            PerformanceDTO performanceDTO = performanceService.convertToDTO(
                    performance.orElse(null),
                    control
            );

            return ResponseEntity.ok(performanceDTO);
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/initiate")
    public ResponseEntity<?> initiatePerformance(@ModelAttribute PerformanceDTO performanceDTO) {
        try {
            Control control = controlService.getControlById(performanceDTO.getControlId())
                    .orElseThrow(() -> new RuntimeException("Control not found"));

            ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(control.getId());
            String facilitatorEmail = null;
            if (assignment != null && assignment.getFacilitator() != null && !assignment.getFacilitator().isEmpty()) {
                facilitatorEmail = assignment.getFacilitator().get(0);
            }

            if (facilitatorEmail == null) {
                return ResponseEntity.badRequest().body("Facilitator not assigned to this control");
            }

            // Save performance record without status (we only use control_status now)
            performanceService.savePerformance(performanceDTO, control);

            // Set control status to "Facilitator Review" so it appears in facilitator's My Items
            control.setControlStatus("Facilitator Review");
            controlService.save(control);

            workflowService.initiateWorkflow(control.getId(), facilitatorEmail);

            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error initiating: " + e.getMessage());
        }
    }
}

