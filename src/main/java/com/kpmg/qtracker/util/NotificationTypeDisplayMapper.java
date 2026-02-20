package com.kpmg.qtracker.util;

import org.springframework.stereotype.Component;

import java.util.Locale;

@Component
public class NotificationTypeDisplayMapper {

    private static final String CLASS_ACTIVATED = "badge-activated";
    private static final String CLASS_REMINDER = "badge-reminder";
    private static final String CLASS_OVERDUE = "badge-overdue";
    private static final String CLASS_COMPLETED = "badge-completed";
    private static final String CLASS_AUTO_CREATED = "badge-auto-created";
    private static final String CLASS_DEFAULT = "badge-default";

    public Display map(String type, String title) {
        String normalized = normalize(type);
        String titleText = title != null ? title.toLowerCase(Locale.ROOT) : "";

        if (normalized.isEmpty()) {
            return new Display("Notification", CLASS_DEFAULT);
        }

        if ("DRAFT_INITIATE_REMINDER".equals(normalized)) {
            return new Display("Initiate Control", CLASS_REMINDER);
        }

        if ("INITIATE".equals(normalized) || "CONTROL_INITIATED".equals(normalized)) {
            return new Display("Control Initiated", CLASS_ACTIVATED);
        }

        if ("REMINDER".equals(normalized)) {
            return new Display("Control Reminder (1)", CLASS_REMINDER);
        }

        if ("CONTROL_AUTO_CREATED".equals(normalized)) {
            return new Display("New Control Created", CLASS_AUTO_CREATED);
        }

        if ("COMPLETED".equals(normalized) || "CONTROL_COMPLETED".equals(normalized)) {
            return new Display("Control Completed", CLASS_COMPLETED);
        }

        if ("WORKFLOW_STEP".equals(normalized) && titleText.contains("completed")) {
            return new Display("Control Completed", CLASS_COMPLETED);
        }

        if ("WORKFLOW_STEP".equals(normalized) && titleText.contains("control submitted by")) {
            return new Display("Workflow Update", CLASS_ACTIVATED);
        }

        if ("WORKFLOW_STEP".equals(normalized)) {
            return new Display("Workflow Update", CLASS_DEFAULT);
        }

        if ("STATUS_CHANGE".equals(normalized) || "STATUS".equals(normalized)) {
            return new Display("Status Update", CLASS_DEFAULT);
        }

        if ("COMMENT".equals(normalized)) {
            return new Display("Comment", CLASS_DEFAULT);
        }

        if ("FILE_UPLOAD".equals(normalized)) {
            return new Display("File Upload", CLASS_DEFAULT);
        }

        if (normalized.endsWith("_DAY0")) {
            return new Display("Control Activated", CLASS_ACTIVATED);
        }
        if (normalized.endsWith("_DAY3") || normalized.endsWith("_DAY5")) {
            return new Display("Control Reminder (1)", CLASS_REMINDER);
        }
        if (normalized.endsWith("_DAY6") || normalized.endsWith("_DAY12") || normalized.endsWith("_DAY25")) {
            return new Display("Control Reminder (2)", CLASS_REMINDER);
        }
        if (normalized.contains("OVERDUE")) {
            return new Display("Control Deadline Notification", CLASS_OVERDUE);
        }
        if (normalized.contains("COMPLETED") || normalized.contains("COMPLETE")) {
            return new Display("Control Completed", CLASS_COMPLETED);
        }

        return new Display(titleCase(normalized), CLASS_DEFAULT);
    }

    public boolean isHiddenType(String type) {
        String normalized = normalize(type);
        return false;
    }

    private String normalize(String type) {
        if (type == null) {
            return "";
        }
        return type.trim()
                .replace(' ', '_')
                .replace('-', '_')
                .toUpperCase(Locale.ROOT);
    }

    private String titleCase(String normalized) {
        String[] parts = normalized.toLowerCase(Locale.ROOT).split("_+");
        StringBuilder builder = new StringBuilder();
        for (String part : parts) {
            if (part.isEmpty()) {
                continue;
            }
            if (builder.length() > 0) {
                builder.append(' ');
            }
            builder.append(Character.toUpperCase(part.charAt(0)));
            if (part.length() > 1) {
                builder.append(part.substring(1));
            }
        }
        return builder.length() > 0 ? builder.toString() : "Notification";
    }

    public record Display(String label, String badgeClass) {
    }
}
