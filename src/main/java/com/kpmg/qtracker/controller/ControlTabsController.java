package com.kpmg.qtracker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.dto.*;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.ControlAssignment;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.ControlFrequency;
import com.kpmg.qtracker.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.*;

@Controller
@RequiredArgsConstructor
public class ControlTabsController {

    private final ControlDetailsService controlDetailsService;
    private final ControlAssignmentService controlAssignmentService;
    private final ControlDocumentsService controlDocumentsService;
    private final UserService userService;
    private final ControlService controlService;
    private final AdminAuditService adminAuditService;
    private final MonthlyNotificationService monthlyNotificationService;
    private final QuarterlyNotificationService quarterlyNotificationService;
    private final RecurringNotificationService recurringNotificationService;
    private final AdhocNotificationService adhocNotificationService;
    private final AnnualNotificationService annualNotificationService;
    private final SemiAnnualNotificationService semiAnnualNotificationService;
    private final NotificationService notificationService;
    private final ControlPermissionService controlPermissionService;
    private final PermissionService permissionService;

    @PostMapping("/api/control-details")
    public ResponseEntity<?> saveControlDetails(@Valid @RequestBody ControlDetailsDTO detailsDTO, HttpSession session) {
        System.out.println("🎯 CONTROL DETAILS SAVE REQUEST:");
        System.out.println("Control ID: " + detailsDTO.getControlId());
        System.out.println("Process Name: " + detailsDTO.getProcessName());
        System.out.println("Homogeneity: " + detailsDTO.getHomogeneity());
        System.out.println("References: " + detailsDTO.getReferencesToControl());
        System.out.println("Department: " + detailsDTO.getDepartment());
        System.out.println("================================");

        try {
            User currentUser = (User) session.getAttribute("currentUser");
            if (currentUser == null) {
                return ResponseEntity.status(401).body("User not authenticated");
            }
            Control control = controlService.getControlById(detailsDTO.getControlId()).orElse(null);
            ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(detailsDTO.getControlId());
            ControlPermission permission = controlPermissionService.resolve(control, currentUser, assignment);
            if (!permission.canEdit()) {
                return ResponseEntity.status(403)
                        .body("VALIDATION_ERROR: User does not have permission to edit this control");
            }
            ControlDetailsDTO existingDetails = controlDetailsService.getDetailsByControlId(detailsDTO.getControlId());
            ControlDetailsDTO mergedDetails = mergeControlDetails(existingDetails, detailsDTO, permission);
            Map<String, String> previousValues = new LinkedHashMap<>();
            Map<String, String> newValues = new LinkedHashMap<>();
            List<String> changedFields = new ArrayList<>();

            collectChange(changedFields, previousValues, newValues, "Process Name",
                    existingDetails.getProcessName(), mergedDetails.getProcessName());
            collectChange(changedFields, previousValues, newValues, "Homogeneity",
                    existingDetails.getHomogeneity(), mergedDetails.getHomogeneity());
            collectChange(changedFields, previousValues, newValues, "References to Control",
                    existingDetails.getReferencesToControl(), mergedDetails.getReferencesToControl());
            collectChange(changedFields, previousValues, newValues, "Department",
                    existingDetails.getDepartment(), mergedDetails.getDepartment());
            collectChange(changedFields, previousValues, newValues, "Process Activities",
                    existingDetails.getProcessActivities(), mergedDetails.getProcessActivities());
            collectChange(changedFields, previousValues, newValues, "Other Related Controls",
                    existingDetails.getOtherRelatedControls(), mergedDetails.getOtherRelatedControls());
            collectChange(changedFields, previousValues, newValues, "IT Applications",
                    existingDetails.getItApplications(), mergedDetails.getItApplications());
            collectChange(changedFields, previousValues, newValues, "Control Steps Performed and Results",
                    existingDetails.getControlStepsPerformed(), mergedDetails.getControlStepsPerformed());
            collectChange(changedFields, previousValues, newValues, "SoQM Head/Team Comments",
                    existingDetails.getSoqmHeadComments(), mergedDetails.getSoqmHeadComments());
            collectChange(changedFields, previousValues, newValues, "Process Owner Comments",
                    existingDetails.getProcessOwnerComments(), mergedDetails.getProcessOwnerComments());
            controlDetailsService.saveDetails(mergedDetails);
            logChanges(session, detailsDTO.getControlId(), "Edit Control", changedFields, previousValues, newValues);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error saving details: " + e.getMessage());
        }
    }

    @PostMapping("/api/control-assignment")
    public ResponseEntity<?> saveControlAssignment(@RequestBody ControlAssignmentDTO assignmentDTO, HttpSession session) {
        System.out.println("🎯 CONTROL ASSIGNMENT SAVE REQUEST:");
        System.out.println("Control ID: " + assignmentDTO.getControlId());
        System.out.println("Facilitator: " + assignmentDTO.getFacilitator());
        System.out.println("Control Operator: " + assignmentDTO.getControlOperator());
        System.out.println("SOQM Team: " + assignmentDTO.getSoqmLead());
        System.out.println("Process Owner: " + assignmentDTO.getProcessOwner());
        System.out.println("Control Shared With: " + assignmentDTO.getControlSharedWith());
        System.out.println("Operation Date: " + assignmentDTO.getControlOperationDate());
        System.out.println("================================");

        try {
            User currentUser = (User) session.getAttribute("currentUser");
            if (currentUser == null) {
                return ResponseEntity.status(401).body("User not authenticated");
            }
            Control control = controlService.getControlById(assignmentDTO.getControlId()).orElse(null);
            ControlAssignmentDTO existingAssignment = controlAssignmentService.getAssignmentByControlId(assignmentDTO.getControlId());
            ControlPermission permission = controlPermissionService.resolve(control, currentUser, existingAssignment);
            // Participants must not reassign roles (e.g. appoint themselves Process Owner and complete alone)
            if (!permission.canEditAll()) {
                return ResponseEntity.status(403)
                        .body("VALIDATION_ERROR: Only SoQM Team can change control assignment");
            }
            ControlAssignmentDTO mergedAssignment = mergeControlAssignment(existingAssignment, assignmentDTO);
            String missingField = findMissingAssignmentField(mergedAssignment);
            if (missingField != null) {
                return ResponseEntity.badRequest().body("VALIDATION_ERROR: " + missingField + " is required");
            }
            String frequencyError = findScheduleFrequencyError(control, mergedAssignment);
            if (frequencyError != null) {
                return ResponseEntity.badRequest().body("VALIDATION_ERROR: " + frequencyError);
            }
            Map<String, String> previousValues = new LinkedHashMap<>();
            Map<String, String> newValues = new LinkedHashMap<>();
            List<String> changedFields = new ArrayList<>();

            collectChange(changedFields, previousValues, newValues, "Facilitator",
                    existingAssignment.getFacilitator(), mergedAssignment.getFacilitator());
            collectChange(changedFields, previousValues, newValues, "Control Operator",
                    existingAssignment.getControlOperator(), mergedAssignment.getControlOperator());
            collectChange(changedFields, previousValues, newValues, "SoQM Team",
                    existingAssignment.getSoqmLead(), mergedAssignment.getSoqmLead());
            collectChange(changedFields, previousValues, newValues, "Process Owner",
                    existingAssignment.getProcessOwner(), mergedAssignment.getProcessOwner());
            collectChange(changedFields, previousValues, newValues, "Control Shared With",
                    existingAssignment.getControlSharedWith(), mergedAssignment.getControlSharedWith());
            collectChange(changedFields, previousValues, newValues, "Control Operation Date",
                    existingAssignment.getControlOperationDate(), mergedAssignment.getControlOperationDate());

            ControlAssignment saved;
            try {
                saved = controlAssignmentService.saveAssignment(mergedAssignment);
            } catch (IllegalArgumentException refused) {
                // Someone who may not hold that field (read-only, wrong level, KDN scope on a non-KDN control)
                return ResponseEntity.badRequest().body("VALIDATION_ERROR: " + refused.getMessage());
            }
            // The service calculates both from the date and the frequency; the log shows what it stored
            collectChange(changedFields, previousValues, newValues, "Control Operation Deadline",
                    existingAssignment.getControlOperationDeadline(), saved.getControlOperationDeadline());
            collectChange(changedFields, previousValues, newValues, "Next Control Operation Date",
                    existingAssignment.getNextControlOperationDate(), saved.getNextControlOperationDate());

            // Send notifications to newly shared users
            List<String> oldShared = existingAssignment != null && existingAssignment.getControlSharedWith() != null
                    ? existingAssignment.getControlSharedWith() : List.of();
            List<String> newShared = mergedAssignment.getControlSharedWith() != null
                    ? mergedAssignment.getControlSharedWith() : List.of();
            Set<String> oldSharedSet = new LinkedHashSet<>();
            for (String e : oldShared) {
                if (e != null && !e.isBlank()) oldSharedSet.add(e.trim().toLowerCase());
            }
            for (String email : newShared) {
                if (email != null && !email.isBlank() && !oldSharedSet.contains(email.trim().toLowerCase())) {
                    notificationService.sendSharedWithNotification(control, email.trim(), currentUser.getDisplayName());
                }
            }

            logChanges(session, assignmentDTO.getControlId(), "Edit Control", changedFields, previousValues, newValues);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error saving assignment: " + e.getMessage());
        }
    }

    @PostMapping("/api/control-documents")
    public ResponseEntity<?> saveControlDocuments(@RequestBody ControlDocumentsDTO documentsDTO, HttpSession session) {
        System.out.println("🎯 CONTROL DOCUMENTS SAVE REQUEST:");
        System.out.println("Control ID: " + documentsDTO.getControlId());
        System.out.println("SOQM Materials: " + documentsDTO.getSoqmDevelopmentMaterials());
        System.out.println("================================");

        try {
            User currentUser = (User) session.getAttribute("currentUser");
            if (currentUser == null) {
                return ResponseEntity.status(401).body("User not authenticated");
            }
            Control control = controlService.getControlById(documentsDTO.getControlId()).orElse(null);
            ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(documentsDTO.getControlId());
            ControlPermission permission = controlPermissionService.resolve(control, currentUser, assignment);
            // SoQM Development Materials belong to the SoQM Team, not to the stage participants
            if (!permission.canEditAll()) {
                return ResponseEntity.status(403)
                        .body("VALIDATION_ERROR: Only SoQM Team can change SoQM Development Materials");
            }
            ControlDocumentsDTO existingDocuments = controlDocumentsService.getDocumentsByControlId(documentsDTO.getControlId());
            ControlDocumentsDTO mergedDocuments = mergeControlDocuments(existingDocuments, documentsDTO);
            Map<String, String> previousValues = new LinkedHashMap<>();
            Map<String, String> newValues = new LinkedHashMap<>();
            List<String> changedFields = new ArrayList<>();

            collectChange(changedFields, previousValues, newValues, "SoQM Development Materials",
                    existingDocuments.getSoqmDevelopmentMaterials(), mergedDocuments.getSoqmDevelopmentMaterials());

            controlDocumentsService.saveDocuments(mergedDocuments);
            logChanges(session, documentsDTO.getControlId(), "Edit Control", changedFields, previousValues, newValues);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            return ResponseEntity.badRequest().body("Error saving documents: " + e.getMessage());
        }
    }

    /**
     * SoQM and admins get every user, for the assignment pickers. Anyone else gets only the people
     * on the control they may read, by name and e-mail, so View Control can show who is assigned.
     */
    @GetMapping("/api/users/all")
    public ResponseEntity<List<UserDTO>> getAllUsers(@RequestParam(required = false) Long controlId,
                                                     HttpSession session) {
        User currentUser = (User) session.getAttribute("currentUser");
        if (AccessPolicy.canListAllUsers(AccessPolicy.Subject.of(currentUser))) {
            List<UserDTO> users = userService.getAllUsers().stream()
                    .map(this::convertToUserDTO)
                    .toList();
            return ResponseEntity.ok(users);
        }
        if (currentUser != null && controlId == null) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        permissionService.requireReadable(controlId, currentUser);

        ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(controlId);
        Set<String> mails = new LinkedHashSet<>();
        for (List<String> group : List.of(nullToEmpty(assignment.getFacilitator()),
                nullToEmpty(assignment.getControlOperator()), nullToEmpty(assignment.getSoqmLead()),
                nullToEmpty(assignment.getProcessOwner()), nullToEmpty(assignment.getControlSharedWith()))) {
            group.stream().filter(mail -> mail != null && !mail.isBlank()).map(String::trim).forEach(mails::add);
        }
        Map<Long, UserDTO> people = new LinkedHashMap<>();
        for (String mail : mails) {
            userService.getUserByEmail(mail).ifPresent(user -> people.putIfAbsent(user.getId(), nameAndMail(user)));
        }
        return ResponseEntity.ok(new ArrayList<>(people.values()));
    }

    /**
     * The people an assignment picker offers (FACILITATOR, CONTROL_OPERATOR, SOQM_TEAM, PROCESS_OWNER,
     * SHARED_WITH): enabled users the field accepts ({@link AccessPolicy#isOfferedFor}); with the control,
     * KDN-scope users only for a KDN control. Only SoQM assigns people, so only SoQM gets the lists.
     */
    @GetMapping("/api/users/role/{role}")
    public ResponseEntity<List<UserDTO>> getUsersByRole(@PathVariable String role,
                                                        @RequestParam(required = false) Long controlId,
                                                        HttpSession session) {
        if (!AccessPolicy.isSoqm(AccessPolicy.Subject.of((User) session.getAttribute("currentUser")))) {
            return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        }
        Optional<AccessPolicy.Slot> slot = AccessPolicy.Slot.forPicker(role);
        if (slot.isEmpty()) {
            return ResponseEntity.badRequest().build();
        }
        boolean kdnControl = controlId != null && controlService.getControlById(controlId)
                .map(control -> AccessPolicy.isKdnControl(control.getControlId()))
                .orElse(false);
        List<UserDTO> users = userService.getUsersOfferedFor(slot.get(), kdnControl).stream()
                .map(this::convertToUserDTO)
                .toList();
        return ResponseEntity.ok(users);
    }

    private static List<String> nullToEmpty(List<String> values) {
        return values == null ? List.of() : values;
    }

    private UserDTO nameAndMail(User user) {
        UserDTO dto = new UserDTO();
        dto.setDisplayName(user.getDisplayName());
        dto.setMail(user.getMail());
        return dto;
    }

    @GetMapping("/api/control-details")
    public ResponseEntity<ControlDetailsDTO> getControlDetails(@RequestParam Long controlId, HttpSession session) {
        permissionService.requireReadable(controlId, (User) session.getAttribute("currentUser"));
        try {
            ControlDetailsDTO details = controlDetailsService.getDetailsByControlId(controlId);
            return ResponseEntity.ok(details);
        } catch (Exception e) {
            return ResponseEntity.ok(new ControlDetailsDTO()); // возвращаем пустой DTO вместо ошибки
        }
    }

    @GetMapping("/api/control-assignment")
    public ResponseEntity<ControlAssignmentDTO> getControlAssignment(@RequestParam Long controlId, HttpSession session) {
        permissionService.requireReadable(controlId, (User) session.getAttribute("currentUser"));
        try {
            ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(controlId);
            return ResponseEntity.ok(assignment);
        } catch (Exception e) {
            return ResponseEntity.ok(new ControlAssignmentDTO()); // возвращаем пустой DTO вместо ошибки
        }
    }

    @GetMapping("/api/control-documents")
    public ResponseEntity<ControlDocumentsDTO> getControlDocuments(@RequestParam Long controlId, HttpSession session) {
        permissionService.requireReadable(controlId, (User) session.getAttribute("currentUser"));
        try {
            ControlDocumentsDTO documents = controlDocumentsService.getDocumentsByControlId(controlId);
            return ResponseEntity.ok(documents);
        } catch (Exception e) {
            return ResponseEntity.ok(new ControlDocumentsDTO()); // возвращаем пустой DTO вместо ошибки
        }
    }

    private UserDTO convertToUserDTO(User user) {
        UserDTO dto = new UserDTO();
        dto.setId(user.getId());
        dto.setDisplayName(user.getDisplayName());
        dto.setMail(user.getMail());
        dto.setTitle(user.getRole());
        dto.setRole(user.getRole());
        dto.setEnabled(Boolean.TRUE.equals(user.getEnabled()));
        return dto;
    }

    private void logChanges(HttpSession session,
                            Long controlId,
                            String description,
                            List<String> changedFields,
                            Map<String, String> previousValues,
                            Map<String, String> newValues) {
        if (changedFields.isEmpty()) {
            return;
        }

        User currentUser = session != null ? (User) session.getAttribute("currentUser") : null;
        if (currentUser == null || controlId == null) {
            return;
        }

        Control control = controlService.getControlById(controlId).orElse(null);
        if (control == null) {
            return;
        }

        try {
            ObjectMapper mapper = new ObjectMapper();
            adminAuditService.logActionWithChanges(
                    currentUser.getMail(),
                    currentUser.getDisplayName(),
                    "EDIT",
                    control,
                    description,
                    mapper.writeValueAsString(changedFields),
                    mapper.writeValueAsString(previousValues),
                    mapper.writeValueAsString(newValues)
            );
        } catch (Exception e) {
            System.out.println("⚠️ Failed to log control changes: " + e.getMessage());
        }
    }

    private ControlDetailsDTO mergeControlDetails(ControlDetailsDTO existing,
                                                  ControlDetailsDTO incoming,
                                                  ControlPermission permission) {
        ControlDetailsDTO merged = new ControlDetailsDTO();
        Long controlId = incoming != null && incoming.getControlId() != null
                ? incoming.getControlId()
                : existing != null ? existing.getControlId() : null;
        merged.setControlId(controlId);

        boolean allowAll = permission != null && permission.canEditAll();
        boolean allowSteps = permission != null && permission.canEditStepsPerformed();
        boolean allowProcessOwner = permission != null && permission.canEditProcessOwnerComments();

        merged.setProcessName(resolveString(existing != null ? existing.getProcessName() : null,
                incoming != null ? incoming.getProcessName() : null, allowAll));
        merged.setHomogeneity(resolveString(existing != null ? existing.getHomogeneity() : null,
                incoming != null ? incoming.getHomogeneity() : null, allowAll));
        merged.setReferencesToControl(resolveString(existing != null ? existing.getReferencesToControl() : null,
                incoming != null ? incoming.getReferencesToControl() : null, allowAll));
        merged.setDepartment(resolveString(existing != null ? existing.getDepartment() : null,
                incoming != null ? incoming.getDepartment() : null, allowAll));
        merged.setProcessActivities(resolveString(existing != null ? existing.getProcessActivities() : null,
                incoming != null ? incoming.getProcessActivities() : null, allowAll));
        merged.setOtherRelatedControls(resolveString(existing != null ? existing.getOtherRelatedControls() : null,
                incoming != null ? incoming.getOtherRelatedControls() : null, allowAll));
        merged.setItApplications(resolveString(existing != null ? existing.getItApplications() : null,
                incoming != null ? incoming.getItApplications() : null, allowAll));

        merged.setControlStepsPerformed(resolveString(existing != null ? existing.getControlStepsPerformed() : null,
                incoming != null ? incoming.getControlStepsPerformed() : null, allowAll || allowSteps));
        merged.setProcessOwnerComments(resolveString(existing != null ? existing.getProcessOwnerComments() : null,
                incoming != null ? incoming.getProcessOwnerComments() : null, allowAll || allowProcessOwner));
        merged.setSoqmHeadComments(resolveString(existing != null ? existing.getSoqmHeadComments() : null,
                incoming != null ? incoming.getSoqmHeadComments() : null, allowAll));

        return merged;
    }

    private ControlDocumentsDTO mergeControlDocuments(ControlDocumentsDTO existing,
                                                      ControlDocumentsDTO incoming) {
        ControlDocumentsDTO merged = new ControlDocumentsDTO();
        Long controlId = incoming != null && incoming.getControlId() != null
                ? incoming.getControlId()
                : existing != null ? existing.getControlId() : null;
        merged.setControlId(controlId);
        merged.setSoqmDevelopmentMaterials(resolveString(existing != null ? existing.getSoqmDevelopmentMaterials() : null,
                incoming != null ? incoming.getSoqmDevelopmentMaterials() : null, true));
        return merged;
    }

    private ControlAssignmentDTO mergeControlAssignment(ControlAssignmentDTO existing,
                                                        ControlAssignmentDTO incoming) {
        ControlAssignmentDTO merged = new ControlAssignmentDTO();
        Long controlId = incoming != null && incoming.getControlId() != null
                ? incoming.getControlId()
                : existing != null ? existing.getControlId() : null;
        merged.setControlId(controlId);

        merged.setFacilitator(resolveList(existing != null ? existing.getFacilitator() : null,
                incoming != null ? incoming.getFacilitator() : null));
        merged.setControlOperator(resolveList(existing != null ? existing.getControlOperator() : null,
                incoming != null ? incoming.getControlOperator() : null));
        merged.setSoqmLead(resolveList(existing != null ? existing.getSoqmLead() : null,
                incoming != null ? incoming.getSoqmLead() : null));
        merged.setProcessOwner(resolveList(existing != null ? existing.getProcessOwner() : null,
                incoming != null ? incoming.getProcessOwner() : null));
        merged.setControlSharedWith(resolveList(existing != null ? existing.getControlSharedWith() : null,
                incoming != null ? incoming.getControlSharedWith() : null));
        merged.setControlOperationDate(resolveDate(existing != null ? existing.getControlOperationDate() : null,
                incoming != null ? incoming.getControlOperationDate() : null));
        // The deadline and the next date are not taken from the request: saveAssignment calculates them

        return merged;
    }

    private String resolveString(String existingValue, String incomingValue, boolean allowUpdate) {
        if (!allowUpdate) {
            return existingValue;
        }
        return incomingValue != null ? incomingValue : existingValue;
    }

    /** Required assignment fields (same as the Save validation in view-control.js). */
    private String findMissingAssignmentField(ControlAssignmentDTO assignment) {
        if (!hasAnyEmail(assignment.getFacilitator())) {
            return "Facilitator";
        }
        if (!hasAnyEmail(assignment.getControlOperator())) {
            return "Control Operator";
        }
        if (!hasAnyEmail(assignment.getSoqmLead())) {
            return "SoQM Team / Delegate";
        }
        if (!hasAnyEmail(assignment.getProcessOwner())) {
            return "Process Owner";
        }
        if (assignment.getControlOperationDate() == null) {
            return "Control Operation Date";
        }
        return null;
    }

    /**
     * saveAssignment calculates the deadline and the next date from the control's frequency, so with an
     * operation date the frequency must be one it knows; otherwise the save failed with "frequency must
     * not be null" or "Unsupported frequency". Same check as Save in view-control.js.
     */
    private String findScheduleFrequencyError(Control control, ControlAssignmentDTO assignment) {
        if (assignment.getControlOperationDate() == null) {
            return null;
        }
        String frequency = control != null ? control.getControlFrequency() : null;
        if (frequency == null || frequency.isBlank()) {
            return "Control Frequency is required to calculate the Control Operation Deadline";
        }
        if (ControlFrequency.tryFromValue(frequency).isEmpty()) {
            return "Control Frequency \"" + frequency.trim() + "\" is not one of " + ControlFrequency.OFFERED
                    + ", so the Control Operation Deadline cannot be calculated";
        }
        return null;
    }

    private boolean hasAnyEmail(List<String> emails) {
        return emails != null && emails.stream().anyMatch(e -> e != null && !e.isBlank());
    }

    private List<String> resolveList(List<String> existingValue, List<String> incomingValue) {
        return incomingValue != null ? incomingValue : existingValue;
    }

    private java.time.LocalDate resolveDate(java.time.LocalDate existingValue, java.time.LocalDate incomingValue) {
        return incomingValue != null ? incomingValue : existingValue;
    }

    private static void collectChange(List<String> changedFields,
                                      Map<String, String> previousValues,
                                      Map<String, String> newValues,
                                      String fieldName,
                                      Object oldValue,
                                      Object newValue) {
        String oldNormalized = normalizeValue(oldValue);
        String newNormalized = normalizeValue(newValue);

        if (!Objects.equals(oldNormalized, newNormalized)) {
            changedFields.add(fieldName);
            previousValues.put(fieldName, oldNormalized);
            newValues.put(fieldName, newNormalized);
        }
    }

    private static String normalizeValue(Object value) {
        if (value == null) return "";
        if (value instanceof Collection<?>) {
            Collection<?> collection = (Collection<?>) value;
            return String.join(", ", collection.stream().map(String::valueOf).collect(java.util.stream.Collectors.toList()));
        }
        return String.valueOf(value).trim();
    }
}
