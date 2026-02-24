package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.*;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.ControlAssignmentRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.util.StatusDisplayMapper;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import com.kpmg.qtracker.service.WorkflowService; // ✅

@Service
@RequiredArgsConstructor
public class ControlService implements IControlService {
    private final ControlRepository controlRepository;
    private final UserService userService;
    private final UserRepository userRepository;
    private final ControlAssignmentRepository controlAssignmentRepository;
    private final StatusDisplayMapper statusDisplayMapper;
    private ControlAssignmentService controlAssignmentService;

    private static final Logger logger = LoggerFactory.getLogger(ControlService.class);

    private WorkflowService workflowService;

    @Override
    public Optional<Control> findById(Long id) {
        return controlRepository.findById(id);
    }

    @Autowired
    @Lazy
    public void setWorkflowService(WorkflowService workflowService) {
        this.workflowService = workflowService;
    }

    @Autowired
    @Lazy
    public void setControlAssignmentService(ControlAssignmentService controlAssignmentService) {
        this.controlAssignmentService = controlAssignmentService;
    }

    // В ControlService добавить метод:
    public List<Control> getControlsForControlOperator(String userEmail) {
        // 1. Получить все контроли
        List<Control> allControls = getAllControls();

        // 2. Filter by status "REVIEW"
        return allControls.stream()
                .filter(control -> "REVIEW".equals(control.getPerformanceStatus()))
                .collect(Collectors.toList());
    }

    public List<Control> getControlsForFacilitator(String userEmail) {
        return getUserControls(userEmail); // уже существует
    }

    public List<Control> getControlsForSoqmLead() {
        // SOQM Lead sees controls in status "SOQM_HEAD_REVIEW"
        return getAllControls().stream()
                .filter(control -> "SOQM_HEAD_REVIEW".equals(control.getPerformanceStatus()))
                .collect(Collectors.toList());
    }

    public List<Control> getControlsForProcessOwner() {
        // Process Owner sees controls in status "PROCESS_OWNER_REVIEW"
        return getAllControls().stream()
                .filter(control -> "PROCESS_OWNER_REVIEW".equals(control.getPerformanceStatus()))
                .collect(Collectors.toList());
    }

    public List<Control> getControlsByPerformanceStatus(String performanceStatus) {
        // Now use performance_status instead
        List<Control> allControls = getAllControls();

        return allControls.stream()
                .filter(control -> performanceStatus.equals(control.getPerformanceStatus()))
                .collect(Collectors.toList());
    }

    @Override
    public List<Control> getAllControls() {
        return controlRepository.findAllByOrderByIdDesc();
    }

    @Override
    public List<Control> getUserControls(String userEmail) {
        logger.info("=== GET USER CONTROLS IMPROVED ===");
        logger.info("User email: {}", userEmail);

        // 1. Найти пользователя по email
        Optional<User> userOpt = userRepository.findByMail(userEmail);

        if (userOpt.isEmpty()) {
            logger.error("User not found with email: {}", userEmail);
            return Collections.emptyList();
        }

        User user = userOpt.get();
        logger.info("Found user: ID={}, Email={}", user.getId(), user.getMail());

        // 2. Найти контроли по ID пользователя (created_by)
        List<Control> controls = controlRepository.findByCreatedBy(user);

        // 3. Если не нашел, попробуем другой способ
        if (controls.isEmpty()) {
            logger.warn("No controls found via findByCreatedBy, trying alternative...");

            // Альтернатива: найти все и отфильтровать
            List<Control> allControls = controlRepository.findAllByOrderByIdDesc();
            controls = allControls.stream()
                    .filter(c -> c.getCreatedBy() != null && user.getId().equals(c.getCreatedBy().getId()))
                    .collect(Collectors.toList());

            logger.info("Alternative method found {} controls", controls.size());
        } else {
            // Сортирую по ID в обратном порядке (новые сверху)
            controls.sort((a, b) -> b.getId().compareTo(a.getId()));
        }

        logger.info("Returning {} controls for user {}", controls.size(), userEmail);
        return controls;
    }

    @Override
    public List<Control> getControlsByComponent(String component) {
        return controlRepository.findByComponentOrderByCreatedAtDesc(component);
    }



    @Override
    public boolean isControlIdUnique(String controlId) {
        Optional<Control> existingControl = controlRepository.findByControlId(controlId);
        return existingControl.isEmpty();
    }

    public Control renameControlId(Long id, String newControlId) {
        Control control = controlRepository.findById(id)
                .orElseThrow(() -> new RuntimeException("Control not found with id: " + id));

        if (control.getControlId().equals(newControlId)) {
            return control;
        }

        if (controlRepository.existsByControlId(newControlId)) {
            throw new RuntimeException("Control ID '" + newControlId + "' already exists. Please choose a different ID.");
        }

        control.setControlId(newControlId);
        return controlRepository.save(control);
    }

    @Override
    public List<ControlResponseDTO> getUserControlsDTO(String userEmail) {
        List<Control> controls = controlRepository.findByCreatedByMailOrderByCreatedAtDesc(userEmail);
        
        // Fix empty performance_status values
        for (Control control : controls) {
            if (control.getPerformanceStatus() == null || control.getPerformanceStatus().trim().isEmpty()) {
                control.setPerformanceStatus("DRAFT");
                logger.info("Fixed control {} performance status to 'DRAFT'", control.getControlId());
            }
        }
        
        logger.info("Converting {} controls to DTO for user: {}", controls.size(), userEmail);
        List<ControlResponseDTO> result = controls.stream()
                .map(this::convertToResponseDTOWithDeadline)
                .collect(Collectors.toList());
        result.forEach(dto -> {
            logger.info("Control {}: status={}, initiated={}",
                    dto.getControlId(), dto.getPerformanceStatus(), dto.isPerformanceInitiated());
        });

        return result;
    }

    @Override
    public List<ControlResponseDTO> getFacilitatorControlsDTO(String userEmail) {
        logger.info("=== GET FACILITATOR CONTROLS DTO ===");
        logger.info("Facilitator email: {}", userEmail);

        // Query only controls where user is facilitator
        List<Long> controlIds = controlAssignmentRepository.findControlIdsByFacilitator(userEmail);
        List<Control> facilitatorControls = controlRepository.findAllById(controlIds);
        
        logger.info("Found {} controls for facilitator: {}", facilitatorControls.size(), userEmail);

        // Convert to DTOs and ensure facilitators are set from controlAssignmentService
        return facilitatorControls.stream()
                .map(control -> {
                    System.out.println("🔄 Converting facilitator control " + control.getControlId() + " to DTO (id=" + control.getId() + ")");
                    ControlResponseDTO dto = convertToResponseDTOWithDeadline(control);
                    System.out.println("   After convertToResponseDTOWithDeadline: facilitators=" + dto.getFacilitators());
                    
                    // Make sure facilitators are loaded from assignment
                    if ((dto.getFacilitators() == null || dto.getFacilitators().isEmpty()) && dto.getId() != null) {
                        System.out.println("   Facilitators empty/null, re-loading from assignment service...");
                        try {
                            ControlAssignmentDTO assignment = this.controlAssignmentService.getAssignmentByControlId(control.getId());
                            System.out.println("   Assignment retrieved: " + (assignment != null ? "NOT NULL" : "NULL"));
                            if (assignment != null) {
                                System.out.println("   Assignment facilitators: " + assignment.getFacilitator());
                                if (assignment.getFacilitator() != null && !assignment.getFacilitator().isEmpty()) {
                                    System.out.println("🔧 Re-loaded facilitators for control " + control.getControlId() + ": " + assignment.getFacilitator());
                                    dto.setFacilitators(assignment.getFacilitator());
                                    System.out.println("   DTO facilitators after set: " + dto.getFacilitators());
                                } else {
                                    System.out.println("   Assignment facilitators is null or empty!");
                                }
                            } else {
                                System.out.println("   Assignment is null!");
                            }
                        } catch (Exception e) {
                            System.out.println("   ERROR re-loading: " + e.getMessage());
                            e.printStackTrace();
                        }
                    } else {
                        System.out.println("   Facilitators already set: " + dto.getFacilitators());
                    }
                    
                    System.out.println("   ✅ Final DTO facilitators: " + dto.getFacilitators());
                    return dto;
                })
                .collect(Collectors.toList());
    }

    private ControlResponseDTO convertToResponseDTOWithDeadline(Control control) {
        ControlResponseDTO dto = convertToResponseDTO(control); // Теперь этот метод уже включает все данные

        try {
            if (this.controlAssignmentService != null && dto.getDeadline() == null) {
                ControlAssignmentDTO assignment = this.controlAssignmentService.getAssignmentByControlId(control.getId());
                if (assignment != null && assignment.getControlOperationDeadline() != null) {
                    dto.setDeadline(assignment.getControlOperationDeadline());
                }
            }
        } catch (Exception e) {
            logger.error("Error getting deadline for control {}: {}", control.getId(), e.getMessage());
        }

        logger.debug("Control {} - Status: {}, Go to cycle: {}",
                control.getControlId(), dto.getPerformanceStatus(), dto.isGoToPerformanceCycle());

        return dto;
    }

    @Override
    public ControlResponseDTO convertToResponseDTO(Control control) {
        ControlResponseDTO dto = new ControlResponseDTO();

        // Основные данные контроля
        dto.setId(control.getId());
        
        // Get raw values from entity
        String controlId = control.getControlId();
        String component = control.getComponent();
        
        // Log raw values for debugging (use INFO level to see in logs)
        logger.info("🔍 Converting control id={}: raw controlId='{}', raw component='{}'", 
            control.getId(), controlId, component);
        
        // Normalize controlId - handle null, empty, or whitespace-only strings
        if (controlId != null) {
            controlId = controlId.trim();
            if (controlId.isEmpty()) {
                controlId = null;
            }
        }
        
        // Normalize component
        if (component != null) {
            component = component.trim();
            if (component.isEmpty()) {
                component = null;
            }
        }
        
        // If controlId is empty/null and component contains numeric value, swap them
        if ((controlId == null || controlId.isEmpty()) && component != null && !component.isEmpty()) {
            // Check if component looks like a numeric control ID (e.g., "1234214", "1")
            if (component.matches("^[0-9]+$")) {
                // Use component as controlId - data was swapped in DB
                logger.warn("Fixed swapped controlId/component for control id={}: component '{}' used as controlId", 
                    control.getId(), component);
                controlId = component;
                // Clear component since it was actually the control ID
                component = null;
            }
        }
        
        // Log final values (use INFO level to see in logs)
        logger.info("✅ Control id={}: final controlId='{}', final component='{}'", 
            control.getId(), controlId, component);
        
        dto.setControlId(controlId);
        dto.setControlFrequency(control.getControlFrequency());
        dto.setControlCategory(control.getControlCategory());
        dto.setControlType(control.getControlType());
        dto.setComponent(component);
        dto.setOperatedBy(control.getOperatedBy());
        dto.setReferencesToControl(control.getReferencesToControl());
        dto.setPriority(control.getPriority());
        dto.setNonAuditServicesApplicability(control.getNonAuditServicesApplicability());
        dto.setHomogeneity(control.getHomogeneity());
        
        // Set UI control status (no workflow defaults here)
        String status = control.getControlStatus();
        dto.setControlStatus(status != null && !status.trim().isEmpty() ? status : null);
        
        dto.setControlDescription(control.getControlDescription());
        dto.setPrp(control.getPrp());

        if (control.getCreatedBy() != null) {
            dto.setCreatedBy(control.getCreatedBy().getDisplayName());
            dto.setCreatedByEmail(control.getCreatedBy().getMail());
            logger.info("Control {}: createdBy='{}', createdByEmail='{}', controlStatus='{}'", 
                control.getControlId(), dto.getCreatedBy(), dto.getCreatedByEmail(), dto.getControlStatus());
        } else {
            dto.setCreatedBy("Unknown");
            dto.setCreatedByEmail(null);
        }

        dto.setCreatedAt(control.getCreatedAt());
        dto.setUpdatedAt(control.getUpdatedAt());

        // Получаем данные assignment
        try {
            if (this.controlAssignmentService != null) {
                ControlAssignmentDTO assignment = this.controlAssignmentService.getAssignmentByControlId(control.getId());

                if (assignment != null) {
                    logger.info("📋 Assignment found for control {}: facilitators={}, operators={}, owners={}, soqm={}", 
                        control.getId(), assignment.getFacilitator(), assignment.getControlOperator(), 
                        assignment.getProcessOwner(), assignment.getSoqmLead());
                    
                    List<String> facilitators = assignment.getFacilitator();
                    logger.info("📋 assignment.getFacilitator() returned: {} (type: {})", 
                        facilitators, 
                        facilitators != null ? facilitators.getClass().getName() : "null");
                    
                    if (facilitators != null && !facilitators.isEmpty()) {
                        facilitators.forEach(fac -> logger.info("   - Facilitator item: '{}'", fac));
                    } else {
                        logger.info("   - Facilitator list is null or empty!");
                    }
                    
                    dto.setFacilitators(facilitators);
                    logger.info("DTO facilitators set to: {}", dto.getFacilitators());
                    logger.info("DTO facilitators type: {}", dto.getFacilitators() != null ? dto.getFacilitators().getClass().getName() : "null");
                    if (dto.getFacilitators() != null && !dto.getFacilitators().isEmpty()) {
                        dto.getFacilitators().forEach(fac -> logger.info("  - In DTO: '{}'", fac));
                    }
                    
                    dto.setControlOperators(assignment.getControlOperator());
                    dto.setProcessOwners(assignment.getProcessOwner());
                    dto.setSoqmLeads(assignment.getSoqmLead());

                    dto.setFacilitatorUsers(convertEmailsToUserDTOs(assignment.getFacilitator()));
                    dto.setControlOperatorUsers(convertEmailsToUserDTOs(assignment.getControlOperator()));
                    dto.setProcessOwnerUsers(convertEmailsToUserDTOs(assignment.getProcessOwner()));
                    dto.setSoqmLeadUsers(convertEmailsToUserDTOs(assignment.getSoqmLead()));

                    dto.setControlOperationDate(assignment.getControlOperationDate());

                    if (assignment.getControlOperationDeadline() != null) {
                        dto.setDeadline(assignment.getControlOperationDeadline());
                    }
                } else {
                    logger.info("📋 No assignment found for control {}", control.getId());
                }
            }
        } catch (Exception e) {
            logger.error("Error getting assignment for control {}: {}", control.getId(), e.getMessage());
        }

        // Ensure string lists are non-null to avoid null pointer in template
        if (dto.getFacilitators() == null) dto.setFacilitators(java.util.List.of());
        if (dto.getControlOperators() == null) dto.setControlOperators(java.util.List.of());
        if (dto.getProcessOwners() == null) dto.setProcessOwners(java.util.List.of());
        if (dto.getSoqmLeads() == null) dto.setSoqmLeads(java.util.List.of());

        // User lists: DO NOT initialize to empty if not populated.
        // Keep null so template fallback to string lists can trigger.
        // Only initialize if assignment was found but conversion failed.
        if (dto.getFacilitatorUsers() == null && !dto.getFacilitators().isEmpty()) {
            dto.setFacilitatorUsers(java.util.List.of());
        }
        if (dto.getControlOperatorUsers() == null && !dto.getControlOperators().isEmpty()) {
            dto.setControlOperatorUsers(java.util.List.of());
        }
        if (dto.getProcessOwnerUsers() == null && !dto.getProcessOwners().isEmpty()) {
            dto.setProcessOwnerUsers(java.util.List.of());
        }
        if (dto.getSoqmLeadUsers() == null && !dto.getSoqmLeads().isEmpty()) {
            dto.setSoqmLeadUsers(java.util.List.of());
        }

        // ★ Если deadline не загружен из assignment, берём из control_controls
        if (dto.getDeadline() == null && control.getDeadline() != null) {
            dto.setDeadline(control.getDeadline());
        }
        
        // Use performance_status for workflow display
        String performanceStatus = control.getPerformanceStatus();
        if (performanceStatus == null || performanceStatus.isEmpty()) {
            performanceStatus = "DRAFT";
        }
        
        dto.setPerformanceStatus(performanceStatus);
        dto.setPerformanceStatusDisplay(statusDisplayMapper.display(performanceStatus));
        
        // Check if control is in workflow (initiated)
        boolean isInitiated = performanceStatus != null
                && !performanceStatus.equals("DRAFT")
                && !performanceStatus.isEmpty();
        dto.setPerformanceInitiated(isInitiated);
        dto.setGoToPerformanceCycle(isInitiated);

        if (this.workflowService != null) {
            try {
                // ★ ИСПОЛЬЗУЕМ getCurrentStep() вместо getCurrentWorkflowStatus()
                WorkflowStepDTO currentStep = workflowService.getCurrentStep(control.getId());
                if (currentStep != null) {
                    dto.setWorkflowStatus(currentStep.getStatus().name());
                    dto.setWorkflowStatusDisplay(currentStep.getStatus().getDisplayName());
                } else {
                    dto.setWorkflowStatus("DRAFT");
                    dto.setWorkflowStatusDisplay("Draft");
                }
            } catch (Exception e) {
                logger.error("Error getting workflow status for control {}: {}", control.getId(), e.getMessage());
                dto.setWorkflowStatus("DRAFT");
                dto.setWorkflowStatusDisplay("Draft");
            }
        } else {
            dto.setWorkflowStatus("DRAFT");
            dto.setWorkflowStatusDisplay("Draft");
        }

        return dto;
    }

    private List<UserDTO> convertEmailsToUserDTOs(List<String> emails) {
        if (emails == null || emails.isEmpty()) {
            logger.info("convertEmailsToUserDTOs: emails is null or empty");
            return Collections.emptyList();
        }

        logger.info("convertEmailsToUserDTOs: processing {} emails: {}", emails.size(), emails);

        return emails.stream()
                .map(email -> {
                    try {
                        Optional<User> userOpt = userService.getUserByEmail(email);
                        if (userOpt.isPresent()) {
                            User user = userOpt.get();
                            logger.info("✅ Found user for email {}: {}", email, user.getDisplayName());
                            return convertToUserDTO(user);
                        } else {
                            logger.info("❌ User not found for email: {}", email);
                            UserDTO userDTO = new UserDTO();
                            userDTO.setMail(email);
                            userDTO.setDisplayName(email); // показываем email как имя
                            return userDTO;
                        }
                    } catch (Exception e) {
                        logger.error("❌ Error converting email {} to UserDTO: {}", email, e.getMessage());
                        UserDTO userDTO = new UserDTO();
                        userDTO.setMail(email);
                        userDTO.setDisplayName(email);
                        return userDTO;
                    }
                })
                .collect(Collectors.toList());
    }

    private UserDTO convertToUserDTO(User user) {
        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setDisplayName(user.getDisplayName());
        dto.setMail(user.getMail());
        dto.setTitle(user.getTitle());
        return dto;
    }

    @Override
    public Control createControl(Control control) {
        try {
            logger.info("Creating control with ID: {}", control.getControlId());

            // ★ ПРОВЕРКА НА ДУБЛИКАТ
            if (controlRepository.existsByControlId(control.getControlId())) {
                throw new IllegalArgumentException(
                        String.format("Control ID '%s' already exists in the system. Please use a different ID.",
                                control.getControlId())
                );
            }

            control.setCreatedAt(LocalDateTime.now());
            control.setUpdatedAt(LocalDateTime.now());

            // Set initial workflow status to DRAFT
            if (control.getPerformanceStatus() == null || control.getPerformanceStatus().isEmpty()) {
                control.setPerformanceStatus("DRAFT");
            }

            Control savedControl = controlRepository.save(control);
            logger.info("Control created successfully: {}", control.getControlId());

            return savedControl;
        } catch (IllegalArgumentException e) {
            logger.warn("Validation error for control ID {}: {}", control.getControlId(), e.getMessage());
            throw e;
        } catch (Exception e) {
            logger.error("Error creating control: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to create control: " + e.getMessage(), e);
        }
    }

    @Override
    public Control save(Control control) {
        logger.info("Saving control with ID: {}", control.getControlId());
        control.setUpdatedAt(LocalDateTime.now());
        return controlRepository.save(control);
    }

    @Override
    public boolean isControlComplete(Control control) {
        return control.getControlFrequency() != null && !control.getControlFrequency().isEmpty()
                && control.getControlType() != null && !control.getControlType().isEmpty()
                && control.getComponent() != null && !control.getComponent().isEmpty()
                && control.getOperatedBy() != null && !control.getOperatedBy().isEmpty()
                && control.getPriority() != null && !control.getPriority().isEmpty()
                && control.getNonAuditServicesApplicability() != null && !control.getNonAuditServicesApplicability().isEmpty();
    }

    @Override
    public Optional<Control> getControlById(Long id) {
        return controlRepository.findById(id);
    }

    @Override
    public void deleteControl(Long id) {
        controlRepository.deleteById(id);
    }

    @Override
    public String getControlFrequency(Long controlId) {
        return getControlById(controlId)
                .map(Control::getControlFrequency)
                .orElse("Monthly");
    }

    @Override
    public Map<String, Long> getComponentStatistics() {
        Map<String, Long> stats = new HashMap<>();

        String[] components = {"HR", "INTR", "M&R", "RAP", "A&C", "I&C", "GOV", "EP", "RER", "TECHR"};
        for (String component : components) {
            Long count = controlRepository.countByComponent(component);
            stats.put(component, count != null ? count : 0L);
        }
        Long totalControls = controlRepository.countAllControls();
        stats.put("All", totalControls != null ? totalControls : 0L);

        return stats;
    }

    // Добавьте этот метод в конец класса ControlService

    public ControlResponseDTO enrichWithWorkflowPermissions(ControlResponseDTO dto, String userEmail) {
        // Проверяем, является ли пользователь facilitator
        boolean isFacilitator = dto.getFacilitators() != null &&
                dto.getFacilitators().contains(userEmail);

        boolean isControlOperator = dto.getControlOperators() != null &&
                dto.getControlOperators().contains(userEmail);

        boolean isSoqmLead = dto.getSoqmLeads() != null &&
                dto.getSoqmLeads().contains(userEmail);

        boolean isProcessOwner = dto.getProcessOwners() != null &&
                dto.getProcessOwners().contains(userEmail);

        String workflowStatus = dto.getWorkflowStatus();

        dto.setCanInitiateWorkflow(
                "DRAFT".equals(workflowStatus) && isFacilitator
        );

        dto.setCanSubmitForReview(
                "IN_PROGRESS".equals(workflowStatus) && isFacilitator
        );

        dto.setCanSubmitForSoQM(
                "REVIEW".equals(workflowStatus) && isControlOperator
        );

        dto.setCanSendToProcessOwner(
                "SOQM_HEAD_REVIEW".equals(workflowStatus) && isSoqmLead
        );

        dto.setCanCompleteWorkflow(
                "PROCESS_OWNER_REVIEW".equals(workflowStatus) && isProcessOwner
        );

        dto.setCanReturnWorkflow(
                !"DRAFT".equals(workflowStatus) &&
                        !"COMPLETED".equals(workflowStatus)
        );

        return dto;
    }

    @Override
    public Control updateControl(Control control) {
        control.setUpdatedAt(LocalDateTime.now());
        return controlRepository.save(control);
    }

    /**
     * Получить контролы ДЛЯ ДАШБОРДА - используем ТУ ЖЕ логику как на странице /controls
     * Считаем контролы так же как на странице controls
     */
    public List<Control> getAllUserControls(String userEmail) {
        logger.info("=== GET ALL USER CONTROLS FOR DASHBOARD (SAME AS /controls) ===");
        logger.info("User email: {}", userEmail);

        Set<Control> allControls = new HashSet<>();

        // 1. Сначала добавляем контролы созданные пользователем
        List<Control> userCreatedControls = controlRepository.findByCreatedByMailOrderByCreatedAtDesc(userEmail);
        logger.info("Found {} controls created by user", userCreatedControls.size());
        allControls.addAll(userCreatedControls);

        // 2. Add controls where user is assigned as Facilitator (regardless of global role)
        {
            logger.info("Checking Facilitator assignments for user");
            
            List<Control> allControlsList = controlRepository.findAll();
            for (Control control : allControlsList) {
                if ("IN_PROGRESS".equals(control.getPerformanceStatus())) {
                    Optional<ControlAssignment> assignmentOpt = controlAssignmentRepository.findByControlId(control.getId());
                    if (assignmentOpt.isPresent()) {
                        ControlAssignment assignment = assignmentOpt.get();
                        String facilitator = assignment.getFacilitator();
                        
                        if (facilitator != null && facilitator.contains(userEmail)) {
                            allControls.add(control);
                            logger.info("✅ Control {} assigned to facilitator {}", control.getControlId(), userEmail);
                        }
                    }
                }
            }
        }

        logger.info("Found {} total controls for user: {}", allControls.size(), userEmail);
        return new ArrayList<>(allControls);
    }

    /**
     * Получить контролы где юзер является Facilitator (по control_assignments)
     */
    public List<Control> getFacilitatorControls(String userEmail) {
        logger.info("=== GET FACILITATOR CONTROLS ===");
        logger.info("Facilitator email: {}", userEmail);

        List<Control> result = new ArrayList<>();
        List<ControlAssignment> assignments = controlAssignmentRepository.findAll();
        
        logger.info("Total assignments found: {}", assignments.size());

        for (ControlAssignment ca : assignments) {
            logger.info("Assignment for control {}: facilitator={}", ca.getControlId(), ca.getFacilitator());
            if (ca.getFacilitator() != null && ca.getFacilitator().contains(userEmail)) {
                logger.info("✅ Match found! User {} is facilitator", userEmail);
                Optional<Control> control = controlRepository.findById(ca.getControlId());
                control.ifPresent(result::add);
            }
        }

        logger.info("Found {} controls for facilitator: {}", result.size(), userEmail);
        return result;
    }

    /**
     * Получить контролы где юзер является Control Operator (по control_assignments)
     */
    public List<Control> getControlOperatorControls(String userEmail) {
        logger.info("=== GET CONTROL OPERATOR CONTROLS ===");
        logger.info("Control Operator email: {}", userEmail);

        List<Long> controlIds = controlAssignmentRepository.findControlIdsByControlOperator(userEmail);
        List<Control> result = controlRepository.findAllById(controlIds);

        logger.info("Found {} controls for control operator: {}", result.size(), userEmail);
        return result;
    }

    /**
     * Получить контролы где юзер является Process Owner (по control_assignments)
     */
    public List<Control> getProcessOwnerControls(String userEmail) {
        logger.info("=== GET PROCESS OWNER CONTROLS ===");
        logger.info("Process Owner email: {}", userEmail);

        List<Long> controlIds = controlAssignmentRepository.findControlIdsByProcessOwner(userEmail);
        List<Control> result = controlRepository.findAllById(controlIds);

        logger.info("Found {} controls for process owner: {}", result.size(), userEmail);
        return result;
    }

    /**
     * Получить контролы где юзер является SoQM Lead (по control_assignments)
     */
    public List<Control> getSoqmLeadControls(String userEmail) {
        logger.info("=== GET SOQM LEAD CONTROLS ===");
        logger.info("SoQM Lead email: {}", userEmail);

        List<Long> controlIds = controlAssignmentRepository.findControlIdsBySoqmLead(userEmail);
        List<Control> result = controlRepository.findAllById(controlIds);

        logger.info("Found {} controls for soqm lead: {}", result.size(), userEmail);
        return result;
    }

    public List<String> getFacilitatorsForControl(Long controlId) {
        Optional<ControlAssignment> assignment = controlAssignmentRepository.findByControlId(controlId);
        if (assignment.isPresent()) {
            String facilitatorStr = assignment.get().getFacilitator();
            if (facilitatorStr != null && !facilitatorStr.trim().isEmpty()) {
                return java.util.Arrays.stream(facilitatorStr.split(","))
                        .map(String::trim)
                        .filter(s -> !s.isEmpty())
                        .collect(java.util.stream.Collectors.toList());
            }
        }
        return new ArrayList<>();
    }

    /**
     * Check if control has reached user's workflow stage
     * @param controlId Control ID
     * @param userRole User role (FACILITATOR, CONTROL_OPERATOR, SOQM_LEAD, PROCESS_OWNER)
     * @return true if control reached that workflow stage
     */
    public boolean hasReachedUserStage(Long controlId, String userRole) {
        String stageName = mapRoleToStageName(userRole);
        if (stageName == null) {
            return false; // Unknown role
        }
        return workflowService.hasReachedStage(controlId, stageName);
    }

    /**
     * Map user role to workflow stage name
     */
    private String mapRoleToStageName(String userRole) {
        if (userRole == null) return null;
        switch (userRole.toUpperCase()) {
            case "FACILITATOR":
                return "IN_PROGRESS";
            case "CONTROL_OPERATOR":
                return "REVIEW";
            case "SOQM_LEAD":
                return "SOQM_HEAD_REVIEW";
            case "PROCESS_OWNER":
                return "PROCESS_OWNER_REVIEW";
            default:
                return null;
        }
    }
}
