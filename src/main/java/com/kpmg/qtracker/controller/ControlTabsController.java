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
            ControlDetailsDTO existingDetails = controlDetailsService.getDetailsByControlId(detailsDTO.getControlId());
            Map<String, String> previousValues = new LinkedHashMap<>();
            Map<String, String> newValues = new LinkedHashMap<>();
            List<String> changedFields = new ArrayList<>();

            collectChange(changedFields, previousValues, newValues, "Process Name",
                    existingDetails.getProcessName(), detailsDTO.getProcessName());
            collectChange(changedFields, previousValues, newValues, "Homogeneity",
                    existingDetails.getHomogeneity(), detailsDTO.getHomogeneity());
            collectChange(changedFields, previousValues, newValues, "References to Control",
                    existingDetails.getReferencesToControl(), detailsDTO.getReferencesToControl());
            collectChange(changedFields, previousValues, newValues, "Department",
                    existingDetails.getDepartment(), detailsDTO.getDepartment());
            collectChange(changedFields, previousValues, newValues, "Process Activities",
                    existingDetails.getProcessActivities(), detailsDTO.getProcessActivities());
            collectChange(changedFields, previousValues, newValues, "Control Operator's Program",
                    existingDetails.getControlOperatorsProgram(), detailsDTO.getControlOperatorsProgram());
            collectChange(changedFields, previousValues, newValues, "Other Related Controls",
                    existingDetails.getOtherRelatedControls(), detailsDTO.getOtherRelatedControls());
            collectChange(changedFields, previousValues, newValues, "IT Applications",
                    existingDetails.getItApplications(), detailsDTO.getItApplications());
            collectChange(changedFields, previousValues, newValues, "Control Steps Performed and Results",
                    existingDetails.getControlStepsPerformed(), detailsDTO.getControlStepsPerformed());
            collectChange(changedFields, previousValues, newValues, "SoQM Head/Team Comments",
                    existingDetails.getSoqmHeadComments(), detailsDTO.getSoqmHeadComments());
            collectChange(changedFields, previousValues, newValues, "Process Owner Comments",
                    existingDetails.getProcessOwnerComments(), detailsDTO.getProcessOwnerComments());
            collectChange(changedFields, previousValues, newValues, "Attached File",
                    existingDetails.getAttachedFile(), detailsDTO.getAttachedFile());

            controlDetailsService.saveDetails(detailsDTO);
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
        System.out.println("SOQM Lead: " + assignmentDTO.getSoqmLead());
        System.out.println("Process Owner: " + assignmentDTO.getProcessOwner());
        System.out.println("Control Shared With: " + assignmentDTO.getControlSharedWith());
        System.out.println("Operation Date: " + assignmentDTO.getControlOperationDate());
        System.out.println("================================");

        try {
            ControlAssignmentDTO existingAssignment = controlAssignmentService.getAssignmentByControlId(assignmentDTO.getControlId());
            Map<String, String> previousValues = new LinkedHashMap<>();
            Map<String, String> newValues = new LinkedHashMap<>();
            List<String> changedFields = new ArrayList<>();

            collectChange(changedFields, previousValues, newValues, "Facilitator",
                    existingAssignment.getFacilitator(), assignmentDTO.getFacilitator());
            collectChange(changedFields, previousValues, newValues, "Control Operator",
                    existingAssignment.getControlOperator(), assignmentDTO.getControlOperator());
            collectChange(changedFields, previousValues, newValues, "SoQM Lead",
                    existingAssignment.getSoqmLead(), assignmentDTO.getSoqmLead());
            collectChange(changedFields, previousValues, newValues, "Process Owner",
                    existingAssignment.getProcessOwner(), assignmentDTO.getProcessOwner());
            collectChange(changedFields, previousValues, newValues, "Control Shared With",
                    existingAssignment.getControlSharedWith(), assignmentDTO.getControlSharedWith());
            collectChange(changedFields, previousValues, newValues, "Control Operation Date",
                    existingAssignment.getControlOperationDate(), assignmentDTO.getControlOperationDate());
            collectChange(changedFields, previousValues, newValues, "Control Operation Deadline",
                    existingAssignment.getControlOperationDeadline(), assignmentDTO.getControlOperationDeadline());
            collectChange(changedFields, previousValues, newValues, "Next Control Operation Date",
                    existingAssignment.getNextControlOperationDate(), assignmentDTO.getNextControlOperationDate());

            controlAssignmentService.saveAssignment(assignmentDTO);
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
        System.out.println("Link: " + documentsDTO.getLink());
        System.out.println("Attachment: " + documentsDTO.getAttachment());
        System.out.println("SOQM Materials: " + documentsDTO.getSoqmDevelopmentMaterials());
        System.out.println("================================");

        try {
            ControlDocumentsDTO existingDocuments = controlDocumentsService.getDocumentsByControlId(documentsDTO.getControlId());
            Map<String, String> previousValues = new LinkedHashMap<>();
            Map<String, String> newValues = new LinkedHashMap<>();
            List<String> changedFields = new ArrayList<>();

            collectChange(changedFields, previousValues, newValues, "Link",
                    existingDocuments.getLink(), documentsDTO.getLink());
            collectChange(changedFields, previousValues, newValues, "Attachment",
                    existingDocuments.getAttachment(), documentsDTO.getAttachment());
            collectChange(changedFields, previousValues, newValues, "SoQM Development Materials",
                    existingDocuments.getSoqmDevelopmentMaterials(), documentsDTO.getSoqmDevelopmentMaterials());

            controlDocumentsService.saveDocuments(documentsDTO);
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
        dto.setDepartment(user.getDepartment());
        dto.setTitle(user.getTitle());
        dto.setOffice(user.getOffice());
        dto.setRole(user.getRole());
        dto.setUsername(user.getUsername());
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
