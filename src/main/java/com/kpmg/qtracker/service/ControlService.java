package com.kpmg.qtracker.service;

import com.kpmg.qtracker.dto.*;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.Notification;
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

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;
import com.kpmg.qtracker.service.WorkflowService; // ✅
import com.kpmg.qtracker.util.EmailList;
import com.kpmg.qtracker.util.RoleDisplayMapper;

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

    @Override
    public List<Control> getAllControls() {
        return controlRepository.findAllByOrderByIdDesc();
    }

    /**
     * The controls the user sees ({@link AccessPolicy#canView}): every control for SoQM Team and All
     * controls; every KDN control for KDN; otherwise the ones they are assigned to, shared with or created.
     */
    @Override
    public List<Control> findVisibleControlsForUser(User user) {
        if (user == null || user.getMail() == null || user.getMail().isBlank()) {
            return Collections.emptyList();
        }
        AccessPolicy.Subject subject = AccessPolicy.Subject.of(user);
        if (AccessPolicy.seesAllControls(subject)) {
            return getAllControls();
        }
        String userEmail = user.getMail();

        List<Control> candidates;
        if (AccessPolicy.seesWithoutBeingOn(subject)) {
            // KDN: every control is a candidate, the policy keeps the KDN ones (the rule stays in one place)
            candidates = getAllControls();
        } else {
            // LIKE finds the address anywhere in a column, including inside another address
            // (a@kpmg.kz in ba@kpmg.kz); the policy below checks whole addresses
            Set<Long> candidateIds = new LinkedHashSet<>();
            addVisibleIds(candidateIds, controlAssignmentRepository.findControlIdsByFacilitator(userEmail));
            addVisibleIds(candidateIds, controlAssignmentRepository.findControlIdsByControlOperator(userEmail));
            addVisibleIds(candidateIds, controlAssignmentRepository.findControlIdsBySoqmLead(userEmail));
            addVisibleIds(candidateIds, controlAssignmentRepository.findControlIdsByProcessOwner(userEmail));
            addVisibleIds(candidateIds, controlAssignmentRepository.findControlIdsByControlSharedWith(userEmail));
            // My controls also cover the controls the user created
            controlRepository.findByCreatedByMailOrderByCreatedAtDesc(userEmail)
                    .forEach(control -> candidateIds.add(control.getId()));
            if (candidateIds.isEmpty()) {
                return Collections.emptyList();
            }
            candidates = controlRepository.findAllById(candidateIds);
        }
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        Map<Long, ControlAssignment> assignments = controlAssignmentRepository.findAllById(
                        candidates.stream().map(Control::getId).filter(Objects::nonNull).toList()).stream()
                .collect(Collectors.toMap(ControlAssignment::getControlId, assignment -> assignment, (a, b) -> a));
        List<Control> visibleControls = candidates.stream()
                .filter(control -> AccessPolicy.canView(subject,
                        facts(control, assignments.get(control.getId()), user)))
                .collect(Collectors.toList());
        visibleControls.sort(Comparator.comparing(Control::getId, Comparator.nullsLast(Long::compareTo)).reversed());
        return visibleControls;
    }

    private AccessPolicy.ControlFacts facts(Control control, ControlAssignment assignment, User user) {
        boolean hasAssignment = assignment != null;
        String userEmail = user.getMail();
        return new AccessPolicy.ControlFacts(
                control.getPerformanceStatus(),
                AccessPolicy.isKdnControl(control.getControlId()),
                hasAssignment && EmailList.contains(assignment.getFacilitator(), userEmail),
                hasAssignment && EmailList.contains(assignment.getControlOperator(), userEmail),
                hasAssignment && EmailList.contains(assignment.getSoqmLead(), userEmail),
                hasAssignment && EmailList.contains(assignment.getProcessOwner(), userEmail),
                hasAssignment && EmailList.contains(assignment.getControlSharedWith(), userEmail),
                ControlPermissionService.isCreator(control, user));
    }



    @Override
    public boolean isControlIdUnique(String controlId) {
        Optional<Control> existingControl = controlRepository.findByControlId(controlId);
        return existingControl.isEmpty();
    }

    @Override
    public ControlResponseDTO convertToResponseDTO(Control control) {
        ControlAssignmentDTO assignment = null;
        try {
            if (this.controlAssignmentService != null) {
                assignment = this.controlAssignmentService.getAssignmentByControlId(control.getId());
            }
        } catch (Exception e) {
            logger.error("Error getting assignment for control {}: {}", control.getId(), e.getMessage());
        }
        return toResponseDTO(control, assignment, userService::getUserByEmail, true);
    }

    /**
     * The rows of a list (Controls, dashboard, Action Centre): the assignments of all the controls in one query
     * and their people in one more, instead of about seven queries per control (N+1).
     */
    @Override
    public List<ControlResponseDTO> convertToResponseDTOs(List<Control> controls) {
        List<ControlResponseDTO> rows = new ArrayList<>();
        if (controls == null || controls.isEmpty()) {
            return rows;
        }
        List<Long> ids = controls.stream().map(Control::getId).filter(Objects::nonNull).toList();
        Map<Long, ControlAssignmentDTO> assignments = this.controlAssignmentService != null
                ? this.controlAssignmentService.getAssignmentsByControlIds(ids) : Map.of();
        Set<String> mails = new HashSet<>();
        for (ControlAssignmentDTO assignment : assignments.values()) {
            for (List<String> people : Arrays.asList(assignment.getFacilitator(), assignment.getControlOperator(),
                    assignment.getProcessOwner(), assignment.getSoqmLead())) {
                if (people != null) {
                    mails.addAll(people);
                }
            }
        }
        Map<String, User> users = userService.usersByEmail(mails);
        java.util.function.Function<String, Optional<User>> lookup = mail -> mail == null ? Optional.empty()
                : Optional.ofNullable(users.get(mail.trim().toLowerCase(Locale.ROOT)));
        for (Control control : controls) {
            // As getAssignmentByControlId: a control without an assignment row gets an empty one
            rows.add(toResponseDTO(control, assignments.getOrDefault(control.getId(), new ControlAssignmentDTO()), lookup,
                    false));
        }
        return rows;
    }

    /**
     * @param workflowStep read the current workflow step (one query); the list pages do not show it, their rows
     *                     take the workflow status from the performance status
     */
    private ControlResponseDTO toResponseDTO(Control control, ControlAssignmentDTO assignment,
                                             java.util.function.Function<String, Optional<User>> users,
                                             boolean workflowStep) {
        ControlResponseDTO dto = new ControlResponseDTO();

        // Основные данные контроля
        dto.setId(control.getId());
        
        // Get raw values from entity
        String controlId = control.getControlId();
        String component = control.getComponent();
        
        // Log raw values for debugging (use INFO level to see in logs)
        logger.debug("🔍 Converting control id={}: raw controlId='{}', raw component='{}'", 
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
        logger.debug("✅ Control id={}: final controlId='{}', final component='{}'", 
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
        dto.setReopened(control.getReopenedAt() != null);
        
        dto.setControlDescription(control.getControlDescription());
        dto.setPrp(control.getPrp());

        if (control.creatorName() != null) {
            dto.setCreatedBy(control.creatorName());
            dto.setCreatedByEmail(control.creatorEmail());
            logger.debug("Control {}: createdBy='{}', createdByEmail='{}', controlStatus='{}'", 
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
                if (assignment != null) {
                    logger.debug("📋 Assignment found for control {}: facilitators={}, operators={}, owners={}, soqm={}", 
                        control.getId(), assignment.getFacilitator(), assignment.getControlOperator(), 
                        assignment.getProcessOwner(), assignment.getSoqmLead());
                    
                    List<String> facilitators = assignment.getFacilitator();
                    logger.debug("📋 assignment.getFacilitator() returned: {} (type: {})", 
                        facilitators, 
                        facilitators != null ? facilitators.getClass().getName() : "null");
                    
                    if (facilitators != null && !facilitators.isEmpty()) {
                        facilitators.forEach(fac -> logger.debug("   - Facilitator item: '{}'", fac));
                    } else {
                        logger.debug("   - Facilitator list is null or empty!");
                    }
                    
                    dto.setFacilitators(facilitators);
                    logger.debug("DTO facilitators set to: {}", dto.getFacilitators());
                    logger.debug("DTO facilitators type: {}", dto.getFacilitators() != null ? dto.getFacilitators().getClass().getName() : "null");
                    if (dto.getFacilitators() != null && !dto.getFacilitators().isEmpty()) {
                        dto.getFacilitators().forEach(fac -> logger.debug("  - In DTO: '{}'", fac));
                    }
                    
                    dto.setControlOperators(assignment.getControlOperator());
                    dto.setProcessOwners(assignment.getProcessOwner());
                    dto.setSoqmLeads(assignment.getSoqmLead());

                    dto.setFacilitatorUsers(convertEmailsToUserDTOs(assignment.getFacilitator(), users));
                    dto.setControlOperatorUsers(convertEmailsToUserDTOs(assignment.getControlOperator(), users));
                    dto.setProcessOwnerUsers(convertEmailsToUserDTOs(assignment.getProcessOwner(), users));
                    dto.setSoqmLeadUsers(convertEmailsToUserDTOs(assignment.getSoqmLead(), users));

                    dto.setControlOperationDate(assignment.getControlOperationDate());

                    if (assignment.getControlOperationDeadline() != null) {
                        dto.setDeadline(assignment.getControlOperationDeadline());
                    }
                } else {
                    logger.debug("📋 No assignment found for control {}", control.getId());
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
        dto.setDeadline(DeadlineOverdue.deadlineOf(dto.getDeadline(), control.getDeadline()));
        
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

        if (!workflowStep) {
            dto.setWorkflowStatus(performanceStatus);
            dto.setWorkflowStatusDisplay(dto.getPerformanceStatusDisplay());
        } else if (this.workflowService != null) {
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

    private List<UserDTO> convertEmailsToUserDTOs(List<String> emails,
                                                java.util.function.Function<String, Optional<User>> users) {
        if (emails == null || emails.isEmpty()) {
            logger.debug("convertEmailsToUserDTOs: emails is null or empty");
            return Collections.emptyList();
        }

        logger.debug("convertEmailsToUserDTOs: processing {} emails: {}", emails.size(), emails);

        return emails.stream()
                .map(email -> {
                    try {
                        Optional<User> userOpt = users.apply(email);
                        if (userOpt.isPresent()) {
                            User user = userOpt.get();
                            logger.debug("✅ Found user for email {}: {}", email, user.getDisplayName());
                            return convertToUserDTO(user);
                        } else {
                            logger.debug("❌ User not found for email: {}", email);
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
        dto.setTitle(RoleDisplayMapper.access(user));
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

            control.setCreatedAt(LocalDateTime.now(Notification.ZONE));
            control.setUpdatedAt(LocalDateTime.now(Notification.ZONE));

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
        control.setUpdatedAt(LocalDateTime.now(Notification.ZONE));
        return controlRepository.save(control);
    }

    @Override
    public Optional<Control> getControlById(Long id) {
        return controlRepository.findById(id);
    }

    @Override
    public String getControlFrequency(Long controlId) {
        return getControlById(controlId)
                .map(Control::getControlFrequency)
                .orElse("Monthly");
    }

    @Override
    public Control updateControl(Control control) {
        control.setUpdatedAt(LocalDateTime.now(Notification.ZONE));
        return controlRepository.save(control);
    }

    private void addVisibleIds(Set<Long> target, List<Long> source) {
        if (source == null) {
            return;
        }
        for (Long id : source) {
            if (id != null) {
                target.add(id);
            }
        }
    }

}
