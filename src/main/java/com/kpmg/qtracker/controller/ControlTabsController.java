package com.kpmg.qtracker.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.dto.*;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.service.*;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpSession;
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

    @PostMapping("/api/control-details")
    public ResponseEntity<?> saveControlDetails(@RequestBody ControlDetailsDTO detailsDTO, HttpSession session) {
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
            if (permission.isSharedCompleted()) {
                String validationError = validateSharedCompletedDetailsUpdate(existingDetails, detailsDTO, permission);
                if (validationError != null) {
                    return ResponseEntity.status(403).body(validationError);
                }
            }
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
            if (!permission.canEdit()) {
                return ResponseEntity.status(403)
                        .body("VALIDATION_ERROR: User does not have permission to edit this control");
            }
            if (permission.isSharedCompleted()) {
                return ResponseEntity.status(403)
                        .body("VALIDATION_ERROR: Shared users on COMPLETED controls cannot edit assignment fields");
            }
            ControlAssignmentDTO mergedAssignment = mergeControlAssignment(existingAssignment, assignmentDTO);
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
            collectChange(changedFields, previousValues, newValues, "Control Operation Deadline",
                    existingAssignment.getControlOperationDeadline(), mergedAssignment.getControlOperationDeadline());
            collectChange(changedFields, previousValues, newValues, "Next Control Operation Date",
                    existingAssignment.getNextControlOperationDate(), mergedAssignment.getNextControlOperationDate());

            controlAssignmentService.saveAssignment(mergedAssignment);

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
            if (!permission.canEdit()) {
                return ResponseEntity.status(403)
                        .body("VALIDATION_ERROR: User does not have permission to edit this control");
            }
            if (permission.isSharedCompleted()) {
                return ResponseEntity.status(403)
                        .body("VALIDATION_ERROR: Shared users on COMPLETED controls cannot edit document fields");
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

    // Get all users for assignment dropdowns
    @GetMapping("/api/users/all")
    public ResponseEntity<List<UserDTO>> getAllUsers() {
        List<UserDTO> users = userService.getAllUsers().stream()
                .map(this::convertToUserDTO)
                .toList();
        return ResponseEntity.ok(users);
    }

    // Get users filtered by role
    @GetMapping("/api/users/role/{role}")
    public ResponseEntity<List<UserDTO>> getUsersByRole(@PathVariable String role) {
        List<UserDTO> users = userService.getUsersByRole(role).stream()
                .map(this::convertToUserDTO)
                .toList();
        return ResponseEntity.ok(users);
    }

    @GetMapping("/api/control-details")
    public ResponseEntity<ControlDetailsDTO> getControlDetails(@RequestParam Long controlId) {
        try {
            ControlDetailsDTO details = controlDetailsService.getDetailsByControlId(controlId);
            return ResponseEntity.ok(details);
        } catch (Exception e) {
            return ResponseEntity.ok(new ControlDetailsDTO()); // возвращаем пустой DTO вместо ошибки
        }
    }

    @GetMapping("/api/control-assignment")
    public ResponseEntity<ControlAssignmentDTO> getControlAssignment(@RequestParam Long controlId) {
        try {
            ControlAssignmentDTO assignment = controlAssignmentService.getAssignmentByControlId(controlId);
            return ResponseEntity.ok(assignment);
        } catch (Exception e) {
            return ResponseEntity.ok(new ControlAssignmentDTO()); // возвращаем пустой DTO вместо ошибки
        }
    }

    @GetMapping("/api/control-documents")
    public ResponseEntity<ControlDocumentsDTO> getControlDocuments(@RequestParam Long controlId) {
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

    private String validateSharedCompletedDetailsUpdate(ControlDetailsDTO existing,
                                                        ControlDetailsDTO incoming,
                                                        ControlPermission permission) {
        if (incoming == null || permission == null || !permission.isSharedCompleted()) {
            return null;
        }
        ControlDetailsDTO safeExisting = existing != null ? existing : new ControlDetailsDTO();
        Set<String> allowed = permission.getAllowedEditableFields();

        if (!allowed.contains(ControlPermission.FIELD_CONTROL_STEPS_PERFORMED)
                && hasForbiddenChange(incoming.getControlStepsPerformed(), safeExisting.getControlStepsPerformed())) {
            return sharedCompletedDeniedMessage(permission);
        }
        if (!allowed.contains(ControlPermission.FIELD_PROCESS_OWNER_COMMENTS)
                && hasForbiddenChange(incoming.getProcessOwnerComments(), safeExisting.getProcessOwnerComments())) {
            return sharedCompletedDeniedMessage(permission);
        }

        if (hasForbiddenChange(incoming.getProcessName(), safeExisting.getProcessName())
                || hasForbiddenChange(incoming.getHomogeneity(), safeExisting.getHomogeneity())
                || hasForbiddenChange(incoming.getReferencesToControl(), safeExisting.getReferencesToControl())
                || hasForbiddenChange(incoming.getDepartment(), safeExisting.getDepartment())
                || hasForbiddenChange(incoming.getProcessActivities(), safeExisting.getProcessActivities())
                || hasForbiddenChange(incoming.getOtherRelatedControls(), safeExisting.getOtherRelatedControls())
                || hasForbiddenChange(incoming.getItApplications(), safeExisting.getItApplications())
                || hasForbiddenChange(incoming.getSoqmHeadComments(), safeExisting.getSoqmHeadComments())) {
            return sharedCompletedDeniedMessage(permission);
        }
        return null;
    }

    private String sharedCompletedDeniedMessage(ControlPermission permission) {
        Set<String> allowed = permission != null ? permission.getAllowedEditableFields() : Set.of();
        if (allowed.isEmpty()) {
            return "VALIDATION_ERROR: Shared users on COMPLETED controls cannot edit fields for this role";
        }
        return "VALIDATION_ERROR: Shared users on COMPLETED controls can edit only: " + String.join(", ", allowed);
    }

    private boolean hasForbiddenChange(String incoming, String existing) {
        if (incoming == null) {
            return false;
        }
        return !Objects.equals(normalizeString(incoming), normalizeString(existing));
    }

    private String normalizeString(String value) {
        return value == null ? "" : value.trim();
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
        merged.setControlOperationDeadline(resolveDate(existing != null ? existing.getControlOperationDeadline() : null,
                incoming != null ? incoming.getControlOperationDeadline() : null));
        merged.setNextControlOperationDate(resolveDate(existing != null ? existing.getNextControlOperationDate() : null,
                incoming != null ? incoming.getNextControlOperationDate() : null));

        return merged;
    }

    private String resolveString(String existingValue, String incomingValue, boolean allowUpdate) {
        if (!allowUpdate) {
            return existingValue;
        }
        return incomingValue != null ? incomingValue : existingValue;
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
