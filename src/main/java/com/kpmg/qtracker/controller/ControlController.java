package com.kpmg.qtracker.controller;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.dto.ControlDTO;
import com.kpmg.qtracker.dto.ControlResponseDTO;
import com.kpmg.qtracker.dto.ControlHistoryEntryDTO;
import com.kpmg.qtracker.dto.ControlDetailsDTO;
import com.kpmg.qtracker.dto.ControlDocumentsDTO;
import com.kpmg.qtracker.dto.PerformanceDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.ControlRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.service.AdminAuditService;
import com.kpmg.qtracker.service.ControlAuditChangeService;
import com.kpmg.qtracker.service.ControlAssignmentService;
import com.kpmg.qtracker.service.ControlDetailsService;
import com.kpmg.qtracker.service.ControlDocumentsService;
import com.kpmg.qtracker.service.ControlHistoryService;
import com.kpmg.qtracker.service.ControlPermission;
import com.kpmg.qtracker.service.ControlPermissionService;
import com.kpmg.qtracker.service.IControlService;
import com.kpmg.qtracker.service.IPerformanceService;
import com.kpmg.qtracker.service.UserService;
import com.kpmg.qtracker.util.StatusDisplayMapper;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import lombok.RequiredArgsConstructor;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/controls")
@RequiredArgsConstructor
public class ControlController {
    private final IControlService controlService;
    private final UserService userService;
    private final ControlAssignmentService controlAssignmentService;
    private final ControlDetailsService controlDetailsService;
    private final ControlDocumentsService controlDocumentsService;
    private final IPerformanceService performanceService; // ДОБАВЛЕНО: поле для performanceService
    private final AdminAuditService adminAuditService;
    private final ControlHistoryService controlHistoryService;
    private final ControlAuditChangeService controlAuditChangeService;
    private final ControlPermissionService controlPermissionService;
    private final StatusDisplayMapper statusDisplayMapper;
    private final com.kpmg.qtracker.service.ControlIdGeneratorService controlIdGeneratorService;
    private static final Logger logger = LoggerFactory.getLogger(ControlController.class);

    @GetMapping
    public List<ControlResponseDTO> getAllControls() {
        return controlService.getAllControls().stream()
                .map(this::convertToResponseDTO)
                .collect(Collectors.toList());
    }

    /**
     * Generate a Control ID automatically based on component and frequency.
     * GET /api/controls/generate-id?component=HR&frequency=Monthly
     */
    @GetMapping("/generate-id")
    public ResponseEntity<Map<String, String>> generateControlId(
            @RequestParam String component,
            @RequestParam String frequency) {
        try {
            String controlId = controlIdGeneratorService.generateControlId(component, frequency);
            return ResponseEntity.ok(Map.of("controlId", controlId));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @GetMapping("/check-id-unique")
    public ResponseEntity<Map<String, Boolean>> checkControlIdUnique(@RequestParam String controlId) {
        try {
            boolean isUnique = controlService.isControlIdUnique(controlId);
            return ResponseEntity.ok(Collections.singletonMap("unique", isUnique));
        } catch (Exception e) {
            return ResponseEntity.badRequest().build();
        }
    }

    @PostMapping("/{id}/rename-id")
    public ResponseEntity<?> renameControlId(@PathVariable Long id,
                                             @RequestBody Map<String, String> request) {
        try {
            String newControlId = request.get("newControlId");
            if (newControlId == null || newControlId.trim().isEmpty()) {
                return ResponseEntity.badRequest().body("Control ID cannot be empty");
            }

            Control updatedControl = controlService.renameControlId(id, newControlId);
            return ResponseEntity.ok(updatedControl);

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(e.getMessage());
        }
    }

    @GetMapping("/user/{email}")
    public List<ControlResponseDTO> getUserControls(@PathVariable String email) {
        return controlService.getUserControls(email).stream()
                .map(this::convertToResponseDTO)
                .collect(Collectors.toList());
    }

    @GetMapping("/component/{component}")
    public List<ControlResponseDTO> getControlsByComponent(@PathVariable String component) {
        return controlService.getControlsByComponent(component).stream()
                .map(this::convertToResponseDTO)
                .collect(Collectors.toList());
    }

    @GetMapping("/{id}/changelog")
    public ResponseEntity<List<ControlHistoryEntryDTO>> getControlChangelog(@PathVariable Long id) {
        return ResponseEntity.ok(controlHistoryService.getControlHistory(id));
    }

    @PostMapping
    public ResponseEntity<?> createControl(@RequestBody ControlDTO controlDTO, HttpSession session) {
        try {
            logger.info("Creating control with ID: {}", controlDTO.getControlId());

            User currentUser = session != null ? (User) session.getAttribute("currentUser") : null;
            if (currentUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body(Map.of("success", false, "message", "User not authenticated"));
            }
            if (!"SOQM_LEAD".equals(currentUser.getRole())) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body(Map.of("success", false, "message", "Only SOQM_LEAD can create controls"));
            }

            // Проверяем, не пустой ли Control ID
            if (controlDTO.getControlId() == null || controlDTO.getControlId().trim().isEmpty()) {
                Map<String, Object> errorResponse = new HashMap<>();
                errorResponse.put("success", false);
                errorResponse.put("error", "EMPTY_ID");
                errorResponse.put("message", "Control ID cannot be empty");
                errorResponse.put("timestamp", LocalDateTime.now());
                return ResponseEntity.badRequest().body(errorResponse);
            }

            User user = userService.getUserByEmail(currentUser.getMail())
                    .orElseThrow(() -> new RuntimeException("User not found"));

            Control control = new Control();
            control.setControlId(controlDTO.getControlId());
            String canonicalFrequency = canonicalizeFrequency(controlDTO.getControlFrequency());
            if (canonicalFrequency == null) {
                return ResponseEntity.badRequest()
                        .body(Map.of("success", false,
                                "error", "INVALID_FREQUENCY",
                                "message", "Control Frequency must be one of Monthly, Quarterly, Ad-hoc, Recurring, Annual, Semi Annual"));
            }
            control.setControlFrequency(canonicalFrequency);
            control.setControlCategory(controlDTO.getControlCategory());
            control.setControlType(controlDTO.getControlType());
            control.setComponent(controlDTO.getComponent());
            control.setOperatedBy(controlDTO.getOperatedBy());
            control.setReferencesToControl(controlDTO.getReferencesToControl());
            control.setPriority(controlDTO.getPriority());
            control.setNonAuditServicesApplicability(controlDTO.getNonAuditServicesApplicability());
            control.setHomogeneity(controlDTO.getHomogeneity());
            control.setControlStatus(controlDTO.getControlStatus());
            control.setPerformanceStatus("DRAFT"); // Initial workflow status before initiate
            control.setControlDescription(controlDTO.getControlDescription());
            control.setPrp(controlDTO.getPrp());
            control.setCreatedBy(user);

            Control savedControl = controlService.createControl(control);
            logger.info("Control created successfully: {}", savedControl.getControlId());

            // Create ControlAssignment for the new control with provided roles
            try {
                ControlAssignmentDTO assignmentDTO = new ControlAssignmentDTO();
                assignmentDTO.setControlId(savedControl.getId());
                assignmentDTO.setFacilitator(controlDTO.getFacilitator());
                assignmentDTO.setControlOperator(controlDTO.getControlOperator());
                assignmentDTO.setSoqmLead(controlDTO.getSoqmLead());
                assignmentDTO.setProcessOwner(controlDTO.getProcessOwner());
                controlAssignmentService.saveAssignment(assignmentDTO);
                logger.info("ControlAssignment created for control ID: {}", savedControl.getId());
            } catch (Exception e) {
                logger.warn("Failed to create ControlAssignment: {}", e.getMessage());
            }

            return ResponseEntity.ok(convertToResponseDTO(savedControl));

        } catch (IllegalArgumentException e) {
            // ★ Обработка дубликата Control ID
            logger.warn("Duplicate Control ID detected: {}", controlDTO.getControlId());

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", "DUPLICATE_ID");
            errorResponse.put("message", e.getMessage());
            errorResponse.put("controlId", controlDTO.getControlId());
            errorResponse.put("suggestion", generateControlIdSuggestion(controlDTO.getControlId()));
            errorResponse.put("timestamp", LocalDateTime.now());

            return ResponseEntity
                    .status(HttpStatus.CONFLICT) // 409 Conflict
                    .body(errorResponse);

        } catch (RuntimeException e) {
            logger.error("Error creating control: {}", e.getMessage(), e);

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", "VALIDATION_ERROR");
            errorResponse.put("message", e.getMessage());
            errorResponse.put("timestamp", LocalDateTime.now());

            return ResponseEntity.badRequest().body(errorResponse);

        } catch (Exception e) {
            logger.error("Unexpected error creating control: {}", e.getMessage(), e);

            Map<String, Object> errorResponse = new HashMap<>();
            errorResponse.put("success", false);
            errorResponse.put("error", "SERVER_ERROR");
            errorResponse.put("message", "An unexpected error occurred. Please try again.");
            errorResponse.put("timestamp", LocalDateTime.now());

            return ResponseEntity
                    .status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(errorResponse);
        }
    }

    @GetMapping("/export/excel")
    public void exportToExcel(
            @RequestParam(required = false) String component,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "filtered") String exportType,
            @RequestParam(required = false) String userEmail,
            HttpServletResponse response,
            HttpSession session
    ) throws IOException {
        try {
            User currentUser = (User) session.getAttribute("currentUser");
            if (currentUser == null) {
                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                response.setContentType("text/plain");
                response.getWriter().write("User not authenticated");
                return;
            }
            if (!"SOQM_LEAD".equals(currentUser.getRole())) {
                response.setStatus(HttpStatus.FORBIDDEN.value());
                response.setContentType("text/plain");
                response.getWriter().write("Forbidden");
                return;
            }
            logger.info("=== EXPORT PARAMETERS ===");
            logger.info("exportType: {}", exportType);
            logger.info("userEmail: {}", userEmail);
            logger.info("========================");

            List<Control> controls;

            if ("user".equals(exportType) && userEmail != null && !userEmail.isEmpty()) {
                logger.info("Calling getUserControls for: {}", userEmail);
                controls = controlService.getUserControls(userEmail);
                logger.info("getUserControls returned {} controls", controls.size());

                // ★ ЛОГИРУЕМ ДЕТАЛИ
                logger.info("Controls details:");
                for (Control c : controls) {
                    logger.info("  - ID: {}, ControlID: {}, Status: {}",
                            c.getId(), c.getControlId(), c.getPerformanceStatus());
                }
            } else if ("filtered".equals(exportType)) {
                controls = controlService.getAllControls().stream()
                        .filter(control -> component == null || component.isEmpty() ||
                                component.equals(control.getComponent()))
                        .filter(control -> status == null || status.isEmpty() ||
                                (control.getPerformanceStatus() != null && status.equals(control.getPerformanceStatus())))
                        .filter(control -> search == null || search.isEmpty() ||
                                (control.getControlId() != null && control.getControlId().toLowerCase().contains(search.toLowerCase())) ||
                                (control.getControlDescription() != null && control.getControlDescription().toLowerCase().contains(search.toLowerCase())))
                        .collect(Collectors.toList());
            } else {
                controls = controlService.getAllControls();
            }

            logger.info("Exporting {} controls to Excel", controls.size());

            // ★ ПРОВЕРКА ЕСЛИ НЕТ ДАННЫХ
            if (controls.isEmpty()) {
                logger.warn("No controls to export!");
                response.setContentType("text/plain");
                response.getWriter().write("No controls found for export");
                return;
            }

            // Создаем Excel workbook
            Workbook workbook = new XSSFWorkbook();
            Sheet sheet = workbook.createSheet("Controls");

            // Стили для заголовков
            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerFont.setColor(IndexedColors.WHITE.getIndex());
            headerStyle.setFont(headerFont);
            headerStyle.setFillForegroundColor(IndexedColors.DARK_BLUE.getIndex());
            headerStyle.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            headerStyle.setAlignment(HorizontalAlignment.CENTER);

            // Заголовки колонок
            String[] headers = {
                    "№",
                    "CONTROL ID",
                    "Component",
                    "Control Type",
                    "Frequency of Control",
                    "Control operation date",
                    "Control Operator",
                    "Process Owner",
                    "SoQM Head/Delegate",
                    "Deadline",
                    "Performance Status"
            };

            Row headerRow = sheet.createRow(0);
            for (int i = 0; i < headers.length; i++) {
                Cell cell = headerRow.createCell(i);
                cell.setCellValue(headers[i]);
                cell.setCellStyle(headerStyle);
                sheet.setColumnWidth(i, 20 * 256);
            }

            // Формат для даты
            CellStyle dateStyle = workbook.createCellStyle();
            CreationHelper createHelper = workbook.getCreationHelper();
            dateStyle.setDataFormat(createHelper.createDataFormat().getFormat("dd.mm.yyyy"));

            // Заполняем данными
            int rowNum = 1;
            int index = 1;
            DateTimeFormatter dateFormatter = DateTimeFormatter.ofPattern("dd.MM.yyyy");

            for (Control control : controls) {
                Row row = sheet.createRow(rowNum++);

                ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(control.getId());
                String operatorNames = resolveDisplayNames(assignment != null ? assignment.getControlOperator() : null);
                String processOwnerNames = resolveDisplayNames(assignment != null ? assignment.getProcessOwner() : null);
                String soqmNames = resolveDisplayNames(assignment != null ? assignment.getSoqmLead() : null);
                LocalDate operationDate = assignment != null ? assignment.getControlOperationDate() : null;

                int colNum = 0;
                row.createCell(colNum++).setCellValue(index++);
                row.createCell(colNum++).setCellValue(control.getControlId() != null ? control.getControlId() : "");
                row.createCell(colNum++).setCellValue(control.getComponent() != null ? control.getComponent() : "");
                row.createCell(colNum++).setCellValue(control.getControlType() != null ? control.getControlType() : "");
                row.createCell(colNum++).setCellValue(control.getControlFrequency() != null ? control.getControlFrequency() : "");
                row.createCell(colNum++).setCellValue(operationDate != null ? operationDate.format(dateFormatter) : "");
                row.createCell(colNum++).setCellValue(operatorNames);
                row.createCell(colNum++).setCellValue(processOwnerNames);
                row.createCell(colNum++).setCellValue(soqmNames);
                row.createCell(colNum++).setCellValue(control.getDeadline() != null ? control.getDeadline().format(dateFormatter) : "");
                row.createCell(colNum).setCellValue(control.getPerformanceStatus() != null ? control.getPerformanceStatus() : "DRAFT");
            }

            // Авто-размер для последней колонки (описание)
            sheet.setColumnWidth(headers.length - 1, 50 * 256);

            // ★ СОХРАНЕНИЕ НА ДИСК
            String outputDir = "C:\\Sultan\\QTracker\\output";
            File dir = new File(outputDir);
            if (!dir.exists()) {
                dir.mkdirs();
                logger.info("Created directory: {}", outputDir);
            }

            // ★ ГЕНЕРАЦИЯ ИМЕНИ ФАЙЛА
            String timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss"));
            String userPart = "all";
            if (userEmail != null && !userEmail.isEmpty()) {
                userPart = userEmail.split("@")[0];
            }
            String fileName = String.format("QT_%s_%s.xlsx", userPart, timestamp);
            String filePath = outputDir + File.separator + fileName;

            // ★ СОХРАНЯЕМ НА ДИСК
            try (FileOutputStream fileOut = new FileOutputStream(filePath)) {
                workbook.write(fileOut);
                logger.info("Excel file saved to disk: {}", filePath);
                logger.info("File size: {} bytes", new File(filePath).length());
            }

            // ★ ТАКЖЕ ОТДАЕМ ДЛЯ СКАЧИВАНИЯ
            String downloadFilename;
            if ("user".equals(exportType) && userEmail != null) {
                String simpleUserPart = userEmail.split("@")[0];
                downloadFilename = "QTracker_MyControls_" + simpleUserPart + "_" +
                        LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + ".xlsx";
            } else {
                downloadFilename = "QTracker_Controls_" +
                        LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM-dd")) + ".xlsx";
            }

            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + downloadFilename + "\"");

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            workbook.write(outputStream);
            workbook.close();

            response.getOutputStream().write(outputStream.toByteArray());
            response.getOutputStream().flush();

            logger.info("Excel export completed successfully, {} rows exported, saved to: {}",
                    controls.size(), filePath);

        } catch (Exception e) {
            logger.error("Error during Excel export", e);
            response.setStatus(HttpServletResponse.SC_INTERNAL_SERVER_ERROR);
            response.setContentType("text/plain");
            response.getWriter().write("Error generating Excel: " + e.getMessage());
        }
    }

    // ★ Вспомогательный метод для генерации предложений по ID
    private String generateControlIdSuggestion(String originalId) {
        String baseId = originalId.replaceAll("\\s*[-_]?\\d+$", ""); // Удаляем существующий суффикс
        String currentYear = String.valueOf(LocalDateTime.now().getYear());

        List<String> suggestions = new ArrayList<>();
        suggestions.add(baseId + "-01");
        suggestions.add(baseId + "-" + currentYear);
        suggestions.add(baseId + "-A");
        suggestions.add(baseId + "_V2");

        return String.join(", ", suggestions);
    }

    @PutMapping("/{id}")
    public ResponseEntity<?> updateControl(@PathVariable Long id, @RequestBody ControlDTO controlDTO, HttpSession session) {
        try {
            // Get current user from session
            User currentUser = (User) session.getAttribute("currentUser");
            if (currentUser == null) {
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body("User not authenticated");
            }
            String userRole = currentUser.getRole();
            
            Control existingControl = controlService.getControlById(id)
                    .orElseThrow(() -> new RuntimeException("Control not found with id: " + id));
            ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(id);
            ControlPermission permission = controlPermissionService.resolve(existingControl, currentUser, assignment);
            if (!permission.canEdit()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("VALIDATION_ERROR: User does not have permission to edit this control");
            }
            if (permission.isSharedCompleted()) {
                return ResponseEntity.status(HttpStatus.FORBIDDEN)
                        .body("VALIDATION_ERROR: Shared users can edit only allowed fields in Control Details");
            }
            String previousFrequency = existingControl.getControlFrequency();
            String requestedFrequency = controlDTO.getControlFrequency();
            String canonicalFrequency = null;
            Control originalSnapshot = controlAuditChangeService.snapshot(existingControl);
            if (requestedFrequency != null) {
                canonicalFrequency = canonicalizeFrequency(requestedFrequency);
                if (canonicalFrequency == null) {
                    return ResponseEntity.badRequest()
                            .body("VALIDATION_ERROR: Control Frequency must be one of Monthly, Quarterly, Ad-hoc, Recurring, Annual, Semi Annual");
                }
            }

            // ============================================
            // ROLE-BASED FIELD RESTRICTIONS VALIDATION
            // ============================================
            // Facilitator/Control Operator CANNOT modify SoQM and Process Owner comments
            if ("CONTROL_OPERATOR".equals(userRole) || "FACILITATOR".equals(userRole)) {
                if (controlDTO.getSoqmHeadComments() != null && 
                    !controlDTO.getSoqmHeadComments().equals(existingControl.getSoqmHeadComments())) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body("VALIDATION_ERROR: Facilitator/Control Operator cannot modify SoQM Head/Team Comments");
                }
                if (controlDTO.getProcessOwnerComments() != null && 
                    !controlDTO.getProcessOwnerComments().equals(existingControl.getProcessOwnerComments())) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body("VALIDATION_ERROR: Facilitator/Control Operator cannot modify Process Owner Comments");
                }
            }
            
            // SoQM Lead CAN modify soqmHeadComments but NOT processOwnerComments
            if ("SOQM_LEAD".equals(userRole)) {
                if (controlDTO.getProcessOwnerComments() != null && 
                    !controlDTO.getProcessOwnerComments().equals(existingControl.getProcessOwnerComments())) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body("VALIDATION_ERROR: SoQM Lead cannot modify Process Owner Comments");
                }
            }
            
            // Process Owner CAN modify processOwnerComments but NOT soqmHeadComments
            if ("PROCESS_OWNER".equals(userRole)) {
                if (controlDTO.getSoqmHeadComments() != null && 
                    !controlDTO.getSoqmHeadComments().equals(existingControl.getSoqmHeadComments())) {
                    return ResponseEntity.status(HttpStatus.FORBIDDEN)
                            .body("VALIDATION_ERROR: Process Owner cannot modify SoQM Head/Team Comments");
                }
            }
            
            // ADMIN can modify everything

            // ============================================
            // UPDATE ALLOWED FIELDS
            // ============================================
            if (canonicalFrequency != null) {
                existingControl.setControlFrequency(canonicalFrequency);
            }
            if (controlDTO.getControlCategory() != null) {
                existingControl.setControlCategory(controlDTO.getControlCategory());
            }
            if (controlDTO.getControlType() != null) {
                existingControl.setControlType(controlDTO.getControlType());
            }
            if (controlDTO.getComponent() != null) {
                existingControl.setComponent(controlDTO.getComponent());
            }
            if (controlDTO.getOperatedBy() != null) {
                existingControl.setOperatedBy(controlDTO.getOperatedBy());
            }
            if (controlDTO.getReferencesToControl() != null) {
                existingControl.setReferencesToControl(controlDTO.getReferencesToControl());
            }
            if (controlDTO.getPriority() != null) {
                existingControl.setPriority(controlDTO.getPriority());
            }
            if (controlDTO.getNonAuditServicesApplicability() != null) {
                existingControl.setNonAuditServicesApplicability(controlDTO.getNonAuditServicesApplicability());
            }
            if (controlDTO.getHomogeneity() != null) {
                existingControl.setHomogeneity(controlDTO.getHomogeneity());
            }
            // NOTE: DO NOT update performanceStatus here - it should only change via workflow transitions (Submit buttons)
            if (controlDTO.getControlStatus() != null) {
                existingControl.setControlStatus(controlDTO.getControlStatus());
            }
            if (controlDTO.getControlDescription() != null) {
                existingControl.setControlDescription(controlDTO.getControlDescription());
            }
            if (controlDTO.getPrp() != null) {
                existingControl.setPrp(controlDTO.getPrp());
            }
            // Set role-specific comments
            if (controlDTO.getSoqmHeadComments() != null
                    && ("SOQM_LEAD".equals(userRole) || "ADMIN".equals(userRole))) {
                existingControl.setSoqmHeadComments(controlDTO.getSoqmHeadComments());
            }
            if (controlDTO.getProcessOwnerComments() != null
                    && ("PROCESS_OWNER".equals(userRole) || "ADMIN".equals(userRole))) {
                existingControl.setProcessOwnerComments(controlDTO.getProcessOwnerComments());
            }
            
            existingControl.setUpdatedAt(LocalDateTime.now());

            Control updatedControl = controlService.updateControl(existingControl);
            if (canonicalFrequency != null
                    && !Objects.equals(normalizeValue(previousFrequency), normalizeValue(canonicalFrequency))) {
                controlAssignmentService.recalculateSchedule(updatedControl.getId());
            }
            ControlAuditChangeService.ControlAuditChangeSet auditChangeSet =
                    controlAuditChangeService.diff(originalSnapshot, updatedControl);
            if (auditChangeSet.hasChanges()) {
                try {
                    ObjectMapper mapper = new ObjectMapper();
                    adminAuditService.logActionWithChanges(
                            currentUser.getMail(),
                            currentUser.getDisplayName(),
                            "EDIT",
                            updatedControl,
                            "Edit Control",
                            mapper.writeValueAsString(auditChangeSet.getChangedFields()),
                            mapper.writeValueAsString(auditChangeSet.getPreviousValues()),
                            mapper.writeValueAsString(auditChangeSet.getNewValues())
                    );
                } catch (Exception e) {
                    logger.warn("Failed to log control changes: {}", e.getMessage());
                }
            }
            return ResponseEntity.ok(convertToResponseDTO(updatedControl));
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error updating control: " + e.getMessage());
        }
    }

    @GetMapping("/{id}/export/completed")
    public void exportCompletedControl(@PathVariable Long id, HttpServletResponse response, HttpSession session) throws IOException {
        try {
            User currentUser = (User) session.getAttribute("currentUser");
            if (currentUser == null) {
                response.setStatus(HttpStatus.UNAUTHORIZED.value());
                response.setContentType("text/plain");
                response.getWriter().write("User not authenticated");
                return;
            }
            Control control = controlService.getControlById(id)
                    .orElseThrow(() -> new RuntimeException("Control not found with id: " + id));

            // Allow SOQM_LEAD or users the control is shared with
            boolean isSoqmLead = "SOQM_LEAD".equals(currentUser.getRole());
            ControlAssignmentDTO assignmentCheck = controlAssignmentService.getAssignmentByControlId(id);
            boolean isSharedWith = assignmentCheck != null
                    && assignmentCheck.getControlSharedWith() != null
                    && assignmentCheck.getControlSharedWith().stream()
                        .anyMatch(e -> e != null && e.equalsIgnoreCase(currentUser.getMail()));
            if (!isSoqmLead && !isSharedWith) {
                response.setStatus(HttpStatus.FORBIDDEN.value());
                response.setContentType("text/plain");
                response.getWriter().write("Forbidden");
                return;
            }

            String performanceStatus = control.getPerformanceStatus();
            if (performanceStatus == null || performanceStatus.isBlank()) {
                performanceStatus = performanceService.getPerformanceStatusByControlId(control.getId());
            }
            if (!"COMPLETED".equalsIgnoreCase(performanceStatus)) {
                response.setStatus(HttpStatus.FORBIDDEN.value());
                response.setContentType("text/plain");
                response.getWriter().write("Control is not completed");
                return;
            }

            ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(control.getId());
            ControlDetailsDTO details = controlDetailsService.getDetailsByControlId(control.getId());
            ControlDocumentsDTO documents = controlDocumentsService.getDocumentsByControlId(control.getId());
            PerformanceDTO performanceDTO = performanceService.buildPerformanceDTO(control);

            Workbook workbook = new XSSFWorkbook();
            Sheet sheet = workbook.createSheet("Control");

            CellStyle headerStyle = workbook.createCellStyle();
            Font headerFont = workbook.createFont();
            headerFont.setBold(true);
            headerStyle.setFont(headerFont);

            Row headerRow = sheet.createRow(0);
            Cell headerField = headerRow.createCell(0);
            headerField.setCellValue("Field");
            headerField.setCellStyle(headerStyle);
            Cell headerValue = headerRow.createCell(1);
            headerValue.setCellValue("Value");
            headerValue.setCellStyle(headerStyle);

            int rowNum = 1;
            rowNum = addRow(sheet, rowNum, "Control ID", control.getControlId());
            rowNum = addRow(sheet, rowNum, "Control Status", statusDisplayMapper.display(control.getControlStatus()));
            rowNum = addRow(sheet, rowNum, "Control Type", control.getControlType());
            rowNum = addRow(sheet, rowNum, "Control Frequency", control.getControlFrequency());
            rowNum = addRow(sheet, rowNum, "Control Category", control.getControlCategory());
            rowNum = addRow(sheet, rowNum, "Component", control.getComponent());
            rowNum = addRow(sheet, rowNum, "Operated By", control.getOperatedBy());
            rowNum = addRow(sheet, rowNum, "References to this Control", control.getReferencesToControl());
            rowNum = addRow(sheet, rowNum, "Priority", control.getPriority());
            rowNum = addRow(sheet, rowNum, "Non-Audit Services Control Applicability", control.getNonAuditServicesApplicability());
            rowNum = addRow(sheet, rowNum, "Homogeneity", control.getHomogeneity());
            rowNum = addRow(sheet, rowNum, "Control Description", control.getControlDescription());
            rowNum = addRow(sheet, rowNum, "PRP", control.getPrp());
            rowNum = addRow(sheet, rowNum, "SoQM Head/Team Comments", control.getSoqmHeadComments());
            rowNum = addRow(sheet, rowNum, "Process Owner Comments", control.getProcessOwnerComments());

            rowNum = addRow(sheet, rowNum, "Created By",
                    control.getCreatedBy() != null ? control.getCreatedBy().getDisplayName() : null);
            rowNum = addRow(sheet, rowNum, "Created At", formatDateTime(control.getCreatedAt()));
            rowNum = addRow(sheet, rowNum, "Updated At", formatDateTime(control.getUpdatedAt()));
            rowNum = addRow(sheet, rowNum, "Deadline", formatDate(control.getDeadline()));

            if (assignment != null) {
                rowNum = addRow(sheet, rowNum, "Facilitator(s)", joinList(assignment.getFacilitator()));
                rowNum = addRow(sheet, rowNum, "Control Operator(s)", joinList(assignment.getControlOperator()));
                rowNum = addRow(sheet, rowNum, "SoQM Lead/Delegate(s)", joinList(assignment.getSoqmLead()));
                rowNum = addRow(sheet, rowNum, "Process Owner(s)", joinList(assignment.getProcessOwner()));
                rowNum = addRow(sheet, rowNum, "Shared With", joinList(assignment.getControlSharedWith()));
                rowNum = addRow(sheet, rowNum, "Control Operation Date", formatDate(assignment.getControlOperationDate()));
                rowNum = addRow(sheet, rowNum, "Control Operation Deadline", formatDate(assignment.getControlOperationDeadline()));
                rowNum = addRow(sheet, rowNum, "Next Control Operation Date", formatDate(assignment.getNextControlOperationDate()));
            }

            rowNum = addRow(sheet, rowNum, "SoQM Year", performanceDTO.getSoqmYear());
            rowNum = addRow(sheet, rowNum, "Actual Operation Date", formatDate(performanceDTO.getActualOperationDate()));
            rowNum = addRow(sheet, rowNum, "Performance Status", performanceStatus);

            if (details != null) {
                rowNum = addRow(sheet, rowNum, "Process Name", details.getProcessName());
                rowNum = addRow(sheet, rowNum, "Department", details.getDepartment());
                rowNum = addRow(sheet, rowNum, "Process Activities", details.getProcessActivities());
                rowNum = addRow(sheet, rowNum, "Other Related Controls", details.getOtherRelatedControls());
                rowNum = addRow(sheet, rowNum, "IT Applications", details.getItApplications());
                rowNum = addRow(sheet, rowNum, "Control Steps Performed and Results", details.getControlStepsPerformed());
            }

            if (documents != null) {
                rowNum = addRow(sheet, rowNum, "SoQM Development Materials", documents.getSoqmDevelopmentMaterials());
            }

            rowNum = addRow(sheet, rowNum, "Attachments (Details)", extractAttachmentNames(control.getAttachmentDetailsPath()));
            rowNum = addRow(sheet, rowNum, "Attachments (Documents)", extractAttachmentNames(control.getAttachmentDocumentsPath()));

            sheet.setColumnWidth(0, 35 * 256);
            sheet.setColumnWidth(1, 80 * 256);

            String downloadFilename = "QTracker_Control_" +
                    (control.getControlId() != null ? control.getControlId() : "Completed") +
                    "_" + LocalDate.now().format(DateTimeFormatter.ISO_DATE) + ".xlsx";

            response.setContentType("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION,
                    "attachment; filename=\"" + downloadFilename + "\"");

            ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
            workbook.write(outputStream);
            workbook.close();

            response.getOutputStream().write(outputStream.toByteArray());
            response.getOutputStream().flush();
        } catch (Exception e) {
            logger.error("Error exporting completed control: {}", e.getMessage(), e);
            response.setStatus(HttpStatus.INTERNAL_SERVER_ERROR.value());
            response.setContentType("text/plain");
            response.getWriter().write("Error generating Excel: " + e.getMessage());
        }
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> deleteControl(@PathVariable Long id) {
        try {
            logger.info("Deleting control with ID: {}", id);

            Control control = controlService.getControlById(id)
                    .orElseThrow(() -> new RuntimeException("Control not found with id: " + id));

            controlService.deleteControl(id);
            logger.info("Control deleted successfully: {}", id);

            return ResponseEntity.ok().build();
        } catch (Exception e) {
            logger.error("Error deleting control: {}", e.getMessage(), e);
            return ResponseEntity.badRequest().body("Error deleting control: " + e.getMessage());
        }
    }

    private ControlResponseDTO convertToResponseDTO(Control control) {
        ControlResponseDTO dto = new ControlResponseDTO();
        dto.setId(control.getId());
        dto.setControlId(control.getControlId());
        dto.setControlFrequency(control.getControlFrequency());
        dto.setControlCategory(control.getControlCategory());
        dto.setControlType(control.getControlType());
        dto.setComponent(control.getComponent());
        dto.setOperatedBy(control.getOperatedBy());
        dto.setReferencesToControl(control.getReferencesToControl());
        dto.setPriority(control.getPriority());
        dto.setNonAuditServicesApplicability(control.getNonAuditServicesApplicability());
        dto.setHomogeneity(control.getHomogeneity());
        dto.setControlStatus(control.getControlStatus());
        dto.setControlDescription(control.getControlDescription());
        dto.setPrp(control.getPrp());
        dto.setSoqmHeadComments(control.getSoqmHeadComments());
        dto.setProcessOwnerComments(control.getProcessOwnerComments());

        if (control.getCreatedBy() != null) {
            dto.setCreatedBy(control.getCreatedBy().getDisplayName()); // передаем ИМЯ как String
        } else {
            dto.setCreatedBy("Unknown");
        }

        dto.setCreatedAt(control.getCreatedAt());
        dto.setUpdatedAt(control.getUpdatedAt());

        // Получаем данные assignment
        try {
            ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(control.getId());
            if (assignment != null) {
                dto.setFacilitators(assignment.getFacilitator());
                dto.setControlOperators(assignment.getControlOperator());
                dto.setProcessOwners(assignment.getProcessOwner());
                dto.setSoqmLeads(assignment.getSoqmLead());
                dto.setControlOperationDate(assignment.getControlOperationDate());
                dto.setDeadline(assignment.getControlOperationDeadline());
            }
        } catch (Exception e) {
            logger.error("Error getting assignment for control {}: {}", control.getId(), e.getMessage());
        }

        // Получаем performance статус
        try {
            String performanceStatus = performanceService.getPerformanceStatusByControlId(control.getId());
            dto.setPerformanceStatus(performanceStatus);
            dto.setPerformanceStatusDisplay(performanceStatus);
            boolean goToPerformanceCycle = "Initiated".equals(performanceStatus) ||
                    "COMPLETED".equals(performanceStatus);
            dto.setPerformanceInitiated(goToPerformanceCycle);
            dto.setGoToPerformanceCycle(goToPerformanceCycle);
        } catch (Exception e) {
            logger.error("Error getting performance status for control {}: {}", control.getId(), e.getMessage());
            dto.setPerformanceStatus("DRAFT");
            dto.setPerformanceStatusDisplay("Draft");
            dto.setPerformanceInitiated(false);
            dto.setGoToPerformanceCycle(false);
        }

        return dto;
    }

    private static void collectChange(List<String> changedFields,
                                      Map<String, String> previousValues,
                                      Map<String, String> newValues,
                                      String fieldName,
                                      String oldValue,
                                      String newValue) {
        String oldNormalized = normalizeValue(oldValue);
        String newNormalized = normalizeValue(newValue);

        if (!Objects.equals(oldNormalized, newNormalized)) {
            changedFields.add(fieldName);
            previousValues.put(fieldName, oldValue);
            newValues.put(fieldName, newValue);
        }
    }

    private int addRow(Sheet sheet, int rowNum, String field, String value) {
        if (value == null) {
            return rowNum;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return rowNum;
        }
        Row row = sheet.createRow(rowNum++);
        row.createCell(0).setCellValue(field);
        row.createCell(1).setCellValue(trimmed);
        return rowNum;
    }

    private String joinList(List<String> items) {
        if (items == null || items.isEmpty()) return null;
        return String.join(", ", items);
    }

    private String resolveDisplayNames(List<String> emails) {
        if (emails == null || emails.isEmpty()) {
            return "";
        }
        List<String> names = new ArrayList<>();
        for (String email : emails) {
            if (email == null || email.trim().isEmpty()) {
                continue;
            }
            String displayName = userService.getUserByEmail(email.trim())
                    .map(User::getDisplayName)
                    .orElse("");
            if (displayName != null && !displayName.trim().isEmpty()) {
                names.add(displayName.trim());
            }
        }
        return names.isEmpty() ? "" : String.join(", ", names);
    }

    private String formatDate(LocalDate date) {
        if (date == null) return null;
        return date.format(DateTimeFormatter.ofPattern("dd.MM.yyyy"));
    }

    private String formatDateTime(LocalDateTime dateTime) {
        if (dateTime == null) return null;
        return dateTime.format(DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm"));
    }

    private String extractAttachmentNames(String raw) {
        if (raw == null || raw.trim().isEmpty()) return null;
        String[] parts = raw.split(";");
        List<String> names = new ArrayList<>();
        for (String part : parts) {
            String trimmed = part.trim();
            if (trimmed.isEmpty()) continue;
            String name = trimmed;
            int lastSlash = Math.max(trimmed.lastIndexOf('/'), trimmed.lastIndexOf('\\'));
            if (lastSlash >= 0 && lastSlash + 1 < trimmed.length()) {
                name = trimmed.substring(lastSlash + 1);
            }
            names.add(name);
        }
        return names.isEmpty() ? null : String.join(", ", names);
    }

    private static String normalizeValue(String value) {
        return value == null ? "" : value.trim();
    }

    private String canonicalizeFrequency(String raw) {
        if (raw == null) {
            return null;
        }
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        String normalizedNoSpace = normalized.replace(" ", "");
        if (normalizedNoSpace.contains("annual/semi")) {
            return null;
        }
        if (normalized.equals("annually")) {
            return "Annual";
        }
        if (normalized.equals("semi-annually") || normalized.equals("semi annually") || normalized.equals("semiannually")) {
            return "Semi Annual";
        }
        if (normalized.equals("semi-annual") || normalized.equals("semi annual") || normalized.equals("semiannual")) {
            return "Semi Annual";
        }
        if (normalized.equals("annual")) {
            return "Annual";
        }
        if (normalized.equals("monthly")) {
            return "Monthly";
        }
        if (normalized.equals("quarterly")) {
            return "Quarterly";
        }
        if (normalized.equals("recurring") || normalized.equals("recurring/other")) {
            return "Recurring";
        }
        if (normalized.equals("ad-hoc") || normalized.equals("ad hoc")
                || normalized.contains("as-required") || normalized.contains("at least annually")) {
            return "Ad-hoc";
        }
        return null;
    }
}
