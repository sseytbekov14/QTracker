package com.kpmg.qtracker.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kpmg.qtracker.util.StatusDisplayMapper;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

/**
 * The snapshot ControlDeactivation keeps of a deactivated control (admin_audit_log.new_values, JSON), as lines the
 * Admin Panel shows: "Control ID: HR-1", "Status: In Progress", "Facilitator: Anna Smith (a@kpmg.kz)", ... A text that
 * is not such JSON gives one line saying so; nothing is thrown.
 */
public final class DeactivationSnapshot {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final StatusDisplayMapper STATUSES = new StatusDisplayMapper();

    /** The plain fields in the order shown, with their names. */
    private static final Map<String, String> FIELDS = new java.util.LinkedHashMap<>();

    static {
        FIELDS.put("controlId", "Control ID");
        FIELDS.put("description", "Description");
        FIELDS.put("component", "Component");
        FIELDS.put("frequency", "Frequency");
        FIELDS.put("performanceStatus", "Status");
        FIELDS.put("controlStatus", "Control Status");
        FIELDS.put("soqmYear", "SoQM Year");
        FIELDS.put("operationDate", "Control Operation Date");
        FIELDS.put("deadline", "Deadline");
        FIELDS.put("createdAt", "Created");
        FIELDS.put("createdBy", "Created by");
        FIELDS.put("id", "Database id");
    }

    private DeactivationSnapshot() {
    }

    public static List<String> lines(String snapshot) {
        List<String> lines = new ArrayList<>();
        if (snapshot == null || snapshot.isBlank()) {
            return lines;
        }
        JsonNode root;
        try {
            root = JSON.readTree(snapshot);
        } catch (Exception e) {
            lines.add("Snapshot: not readable");
            return lines;
        }
        if (root == null || !root.isObject()) {
            lines.add("Snapshot: not readable");
            return lines;
        }
        for (Map.Entry<String, String> field : FIELDS.entrySet()) {
            JsonNode value = root.get(field.getKey());
            if (value != null && !value.isNull() && !value.asText().isBlank()) {
                String text = "performanceStatus".equals(field.getKey()) ? STATUSES.display(value.asText()) : value.asText();
                // A date and time "2026-11-06T10:15:30.123" reads "2026-11-06 10:15"
                lines.add(field.getValue() + ": " + text.trim()
                        .replaceAll("^(\\d{4}-\\d{2}-\\d{2})T(\\d{2}:\\d{2}).*$", "$1 $2"));
            }
        }
        JsonNode roles = root.get("roles");
        if (roles != null && roles.isObject()) {
            Iterator<Map.Entry<String, JsonNode>> it = roles.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> role = it.next();
                lines.add(role.getKey() + ": " + people(role.getValue()));
            }
        }
        JsonNode files = root.get("files");
        if (files != null && files.isArray()) {
            List<String> names = new ArrayList<>();
            files.forEach(file -> names.add(file.asText()));
            lines.add("Files: " + (names.isEmpty() ? "-" : names.size() + " (" + String.join(", ", names) + ")"));
        }
        return lines;
    }

    private static String people(JsonNode people) {
        if (people == null || !people.isArray() || people.isEmpty()) {
            return "-";
        }
        List<String> shown = new ArrayList<>();
        for (JsonNode person : people) {
            String email = person.path("email").asText("");
            String name = person.path("name").asText("");
            shown.add(name.isBlank() ? email : name + " (" + email + ")");
        }
        return String.join("; ", shown);
    }

}
