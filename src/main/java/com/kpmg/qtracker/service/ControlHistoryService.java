package com.kpmg.qtracker.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.dto.ControlHistoryEntryDTO;
import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.dto.FieldChangeDTO;
import com.kpmg.qtracker.entity.AdminAuditLog;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.WorkflowHistory;
import com.kpmg.qtracker.enums.WorkflowActionType;
import com.kpmg.qtracker.repository.AdminAuditLogRepository;
import com.kpmg.qtracker.repository.ControlRepository;
import com.kpmg.qtracker.repository.WorkflowHistoryRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.*;

@Service
@RequiredArgsConstructor
public class ControlHistoryService {
    private final ControlRepository controlRepository;
    private final ControlAssignmentService controlAssignmentService;
    private final AdminAuditLogRepository adminAuditLogRepository;
    private final WorkflowHistoryRepository workflowHistoryRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public List<ControlHistoryEntryDTO> getControlHistory(Long controlId) {
        List<ControlHistoryEntryDTO> entries = new ArrayList<>();

        Optional<Control> controlOpt = controlRepository.findById(controlId);
        ControlAssignmentDTO assignment = null;
        if (controlOpt.isPresent()) {
            Control control = controlOpt.get();
            assignment = controlAssignmentService.getAssignmentByControlId(controlId);

            ControlHistoryEntryDTO created = new ControlHistoryEntryDTO();
            created.setEventName("New Control");
            created.setTableType("SINGLE");
            created.setCreatedAt(control.getCreatedAt());
            if (control.getCreatedBy() != null) {
                created.setActorName(control.getCreatedBy().getDisplayName());
                created.setActorEmail(control.getCreatedBy().getMail());
            }

            List<FieldChangeDTO> createdRows = new ArrayList<>();
            addValueRow(createdRows, "Control ID", control.getControlId());
            addValueRow(createdRows, "Control Frequency", control.getControlFrequency());
            addValueRow(createdRows, "Control Category", control.getControlCategory());
            addValueRow(createdRows, "Control Type", control.getControlType());
            addValueRow(createdRows, "Component", control.getComponent());
            addValueRow(createdRows, "Operated By", control.getOperatedBy());
            addValueRow(createdRows, "References to this Control", control.getReferencesToControl());
            addValueRow(createdRows, "Priority", control.getPriority());
            addValueRow(createdRows, "Non-Audit Services Control Applicability", control.getNonAuditServicesApplicability());
            addValueRow(createdRows, "Homogeneity", control.getHomogeneity());
            addValueRow(createdRows, "Control Description", control.getControlDescription());
            addValueRow(createdRows, "PRP", control.getPrp());
            created.setFieldChanges(createdRows);
            entries.add(created);
        }

        List<AdminAuditLog> auditLogs = adminAuditLogRepository.findByControlIdOrderByCreatedAtDesc(controlId);
        for (AdminAuditLog log : auditLogs) {
            if (log.getChangedFields() == null && log.getPreviousValues() == null && log.getNewValues() == null) {
                continue;
            }
            List<FieldChangeDTO> changes = parseFieldChanges(log);
            if (changes.isEmpty()) {
                continue;
            }
            ControlHistoryEntryDTO editEntry = new ControlHistoryEntryDTO();
            editEntry.setEventName(mapAuditActionName(log));
            editEntry.setTableType("DIFF");
            editEntry.setCreatedAt(log.getCreatedAt());
            editEntry.setActorName(log.getAdminName());
            editEntry.setActorEmail(log.getAdminEmail());
            editEntry.setFieldChanges(changes);
            entries.add(editEntry);
        }

        List<WorkflowHistory> workflowHistory = workflowHistoryRepository.findByControlIdOrderByCreatedAtDesc(controlId);
        for (WorkflowHistory history : workflowHistory) {
            ControlHistoryEntryDTO workflowEntry = new ControlHistoryEntryDTO();
            workflowEntry.setEventName(mapWorkflowActionName(history, assignment));
            workflowEntry.setCreatedAt(history.getCreatedAt());
            workflowEntry.setActorName(history.getPerformedByName());
            workflowEntry.setActorEmail(history.getPerformedByEmail());
            if (history.getComments() != null && !history.getComments().isBlank()) {
                workflowEntry.setEventDetails(history.getComments());
            }
            entries.add(workflowEntry);
        }

        entries.sort((a, b) -> {
            if (a.getCreatedAt() == null && b.getCreatedAt() == null) return 0;
            if (a.getCreatedAt() == null) return 1;
            if (b.getCreatedAt() == null) return -1;
            return b.getCreatedAt().compareTo(a.getCreatedAt());
        });

        return entries;
    }

    private String mapWorkflowActionName(WorkflowHistory history, ControlAssignmentDTO assignment) {
        WorkflowActionType actionType = history != null ? history.getActionType() : null;
        if (actionType == null) return "Control Performance - Status Update";

        switch (actionType) {
            case INITIATE:
                return "Control Performance - Initiated" + formatOperationDate(assignment);
            case SUBMIT_TO_OPERATOR:
                return "Control Performance - Submitted for Review";
            case SUBMIT_TO_SOQM_LEAD:
                return "Control Performance - Submitted for SoQM Lead / Delegate's Review";
            case SUBMIT_TO_PROCESS_OWNER:
                return "Control Performance - Submitted to Process Owner";
            case APPROVE:
                return "Control Performance - Completed";
            case RETURN_TO_FACILITATOR:
            case RETURN_TO_OPERATOR:
            case RETURN:
            case REJECT:
                return "Control Performance - Returned for Rework";
            case COMMENT:
                return "Review comments on Operation of the Control";
            default:
                return "Control Performance - Status Update";
        }
    }

    private List<FieldChangeDTO> parseFieldChanges(AdminAuditLog log) {
        List<String> fields = parseFieldList(log.getChangedFields());
        Map<String, Object> previous = parseJsonMap(log.getPreviousValues());
        Map<String, Object> updated = parseJsonMap(log.getNewValues());

        if (fields.isEmpty()) {
            fields = new ArrayList<>(previous.keySet());
            fields.addAll(updated.keySet());
            fields = new ArrayList<>(new LinkedHashSet<>(fields));
        }

        List<FieldChangeDTO> changes = new ArrayList<>();
        for (String field : fields) {
            String oldValue = valueToString(previous.get(field));
            String newValue = valueToString(updated.get(field));
            String label = normalizeFieldLabel(field);
            changes.add(new FieldChangeDTO(label, oldValue, newValue));
        }
        return changes;
    }

    private List<String> parseFieldList(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new ArrayList<>();
        String trimmed = raw.trim();
        try {
            if (trimmed.startsWith("[") || trimmed.startsWith("{")) {
                return objectMapper.readValue(trimmed, new TypeReference<List<String>>() {});
            }
        } catch (Exception ignored) {
        }
        String[] parts = trimmed.split(",");
        List<String> result = new ArrayList<>();
        for (String part : parts) {
            String value = part.trim();
            if (!value.isEmpty()) {
                result.add(value);
            }
        }
        return result;
    }

    private Map<String, Object> parseJsonMap(String raw) {
        if (raw == null || raw.trim().isEmpty()) return new LinkedHashMap<>();
        try {
            return objectMapper.readValue(raw, new TypeReference<Map<String, Object>>() {});
        } catch (Exception ignored) {
            return new LinkedHashMap<>();
        }
    }

    private String valueToString(Object value) {
        if (value == null) return "";
        return String.valueOf(value);
    }

    private void addValueRow(List<FieldChangeDTO> rows, String label, Object value) {
        String normalized = valueToString(value).trim();
        if (!normalized.isEmpty()) {
            rows.add(new FieldChangeDTO(label, "", normalized));
        }
    }

    private String normalizeFieldLabel(String field) {
        if (field == null) return "";
        switch (field) {
            case "References to Control":
            case "references_to_control":
                return "References to this Control";
            case "Non-Audit Services Applicability":
            case "non_audit_services_applicability":
                return "Non-Audit Services Control Applicability";
            case "SoQM Lead":
            case "soqm_lead":
                return "SoQM Head/Lead";
            case "control_id":
                return "Control ID";
            case "prp":
                return "PRP";
            case "soqm_head_comments":
                return "SoQM Head/Team Comments";
            case "process_owner_comments":
                return "Process Owner Comments";
            default:
                return humanizeFieldLabel(field);
        }
    }

    private String humanizeFieldLabel(String field) {
        if (field == null || field.isBlank()) {
            return "";
        }
        String normalized = field.replaceAll("([a-z])([A-Z])", "$1 $2")
                .replace('_', ' ')
                .replaceAll("\\s+", " ")
                .trim();
        if (normalized.isEmpty()) {
            return "";
        }

        String[] parts = normalized.split(" ");
        List<String> result = new ArrayList<>(parts.length);
        for (String part : parts) {
            String lower = part.toLowerCase(Locale.ROOT);
            switch (lower) {
                case "id":
                    result.add("ID");
                    break;
                case "prp":
                    result.add("PRP");
                    break;
                case "soqm":
                    result.add("SoQM");
                    break;
                default:
                    result.add(Character.toUpperCase(lower.charAt(0)) + lower.substring(1));
                    break;
            }
        }
        return String.join(" ", result);
    }

    private String mapAuditActionName(AdminAuditLog log) {
        if (log == null) {
            return "Edit Control";
        }
        String actionType = log.getActionType();
        if (actionType == null || actionType.isBlank()) {
            return "Edit Control";
        }
        String tabSuffix = "";
        String description = log.getActionDescription();
        if (description != null) {
            String upper = description.toUpperCase(Locale.ROOT);
            if (upper.contains("DETAILS")) {
                tabSuffix = " (Details)";
            } else if (upper.contains("DOCUMENTS")) {
                tabSuffix = " (Documents)";
            }
        }
        switch (actionType) {
            case "ATTACHMENT_ADDED":
                return "Attachment Added" + tabSuffix;
            case "ATTACHMENT_REMOVED":
                return "Attachment Removed" + tabSuffix;
            case "ATTACHMENT_REPLACED":
                return "Attachment Replaced" + tabSuffix;
            default:
                return "Edit Control";
        }
    }

    private String formatOperationDate(ControlAssignmentDTO assignment) {
        if (assignment == null || assignment.getControlOperationDate() == null) {
            return "";
        }
        java.time.format.DateTimeFormatter formatter =
                java.time.format.DateTimeFormatter.ofPattern("MM/dd/yyyy");
        return " (Operation Date: " + assignment.getControlOperationDate().format(formatter) + ")";
    }
}
