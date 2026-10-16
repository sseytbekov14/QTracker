package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

final class ControlNotificationText {

    private static final DateTimeFormatter DEADLINE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final String EN_DASH = "\u2013";

    private ControlNotificationText() {
    }

    static String dayReminderSubject(int day, Control control) {
        return "[DAY " + day + "] " + EN_DASH + " "
                + formatFrequency(control != null ? control.getControlFrequency() : null)
                + " " + EN_DASH + " Control " + formatControlId(control);
    }

    /** The control's link is the notification's (Notification.link): its "Go to Control" button, not a line here. */
    static String dayReminderBody(int day, Control control, LocalDate operationDate) {
        return "Reminder Type: DAY " + day + "\n"
                + "Frequency: " + formatFrequency(control != null ? control.getControlFrequency() : null) + "\n"
                + "Current Status: " + formatStatus(control != null ? control.getControlStatus() : null) + "\n"
                + "Operation Date: " + formatDeadline(operationDate) + "\n"
                + "\n"
                + "Control ID: " + formatControlId(control) + "\n"
                + "\n"
                + "Kind regards,\n"
                + "SoQM Team";
    }

    static String overdueBody(Control control, LocalDate deadline) {
        String controlIdAndName = formatControlIdAndName(control, ", ");
        return "Dear Facilitator/Operator,\n" +
                "\n" +
                "Our records indicate that the control deadline has passed.\n" +
                "The following control remains incomplete: " + controlIdAndName + "\n" +
                "\n" +
                "Please ensure this control is completed by the end of the day. Click the \"Go to Control\" button below to access and complete the control.\n" +
                "\n" +
                "If you have any questions or require assistance, please let us know.\n" +
                "\n" +
                "Thank you,\n" +
                "\n" +
                "Kind regards,\n" +
                "SoQM Team";
    }

    private static String formatControlIdAndName(Control control, String delimiter) {
        if (control == null) {
            return "Control";
        }
        String id = control.getControlId() != null ? control.getControlId().trim() : "";
        String name = control.getControlDescription() != null ? control.getControlDescription().trim() : "";
        if (id.isEmpty() && name.isEmpty()) {
            return "Control";
        }
        if ("Control".equalsIgnoreCase(id) && !name.isEmpty()) {
            return name;
        }
        if (!id.isEmpty() && !name.isEmpty() && id.equalsIgnoreCase(name)) {
            return id;
        }
        if (name.isEmpty()) {
            return id;
        }
        if (id.isEmpty()) {
            return name;
        }
        return id + delimiter + name;
    }

    private static String formatControlId(Control control) {
        if (control == null) {
            return "Control";
        }
        String id = control.getControlId() != null ? control.getControlId().trim() : "";
        return id.isEmpty() ? "Control" : id;
    }

    private static String formatFrequency(String frequency) {
        if (frequency == null || frequency.trim().isEmpty()) {
            return "Unknown";
        }
        String normalized = frequency.trim().toLowerCase();
        if (normalized.contains("semi") && normalized.contains("annual")) {
            return "Semi-Annual";
        }
        if (normalized.contains("annual")) {
            return "Annual";
        }
        if (normalized.contains("quarter")) {
            return "Quarterly";
        }
        if (normalized.contains("month")) {
            return "Monthly";
        }
        if (normalized.contains("recurr")) {
            return "Recurring";
        }
        if (normalized.contains("ad") && normalized.contains("hoc")) {
            return "Ad-hoc";
        }
        return frequency.trim();
    }

    private static String formatStatus(String status) {
        if (status == null || status.trim().isEmpty()) {
            return "N/A";
        }
        return status.trim();
    }

    private static String formatDeadline(LocalDate deadline) {
        if (deadline == null) {
            return "N/A";
        }
        return deadline.format(DEADLINE_FORMAT);
    }
}
