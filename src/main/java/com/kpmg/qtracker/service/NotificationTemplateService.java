package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationTemplateService {

    @Value("${app.base-url:}")
    private String baseUrl;

    private static final DateTimeFormatter DEADLINE_FORMAT = DateTimeFormatter.ofPattern("dd.MM.yyyy");

    public NotificationTemplate render(TemplateType type,
                                       Control control,
                                       LocalDate deadline,
                                       boolean resubmitted,
                                       String recipientName,
                                       String recipientRole) {
        String controlId = buildControlId(control);
        String controlName = buildControlName(control);
        String controlIdAndNameSlash = buildControlIdAndName(controlId, controlName, " / ");
        String controlIdAndNameComma = buildControlIdAndName(controlId, controlName, ", ");
        String link = buildControlLink(control);
        String deadlineText = formatDeadline(deadline);
        String greeting = buildGreeting(recipientName);

        switch (type) {
            case ACTIVATION:
                return new NotificationTemplate(
                        "Control Initiated",
                        greeting + "\n" +
                                "\n" +
                                "The scheduled control " + controlIdAndNameSlash + " has been activated today. Kindly complete and submit the control as required before the deadline " + deadlineText + ".\n" +
                                "\n" +
                                "Please click the \"Go to Control\" button below to access the control.\n" +
                                "\n" +
                                "Thank you,\n" +
                                "\n" +
                                "Kind regards,\n" +
                                "SoQM Team",
                        "INITIATE"
                );
            case FACILITATOR_TO_OPERATOR:
                return new NotificationTemplate(
                        "Control sent to Control Operator",
                        greeting + "\n" +
                                "\n" +
                                "The control has been completed by the Facilitator. Please review the details by clicking the \"Go to Control\" button below.\n" +
                                "\n" +
                                "Thank you,\n" +
                                "\n" +
                                "Kind regards,\n" +
                                "SoQM Team",
                        "WORKFLOW_STEP"
                );
            case OPERATOR_TO_SOQM:
                if (resubmitted) {
                    return new NotificationTemplate(
                            "Control sent to SoQM Head/Delegate",
                            greeting + "\n" +
                                    "\n" +
                                    "The control has been adjusted by the Control Operator. Please click the \"Go to Control\" button to view the control.\n" +
                                    "\n" +
                                    "Thank you,\n" +
                                    "\n" +
                                    "Kind regards,\n" +
                                    "SoQM Team",
                            "WORKFLOW_STEP"
                    );
                }
                return new NotificationTemplate(
                        "Control ready for review by Control Operator",
                        greeting + "\n" +
                                    "\n" +
                                    "The control has been completed by the Control Operator. Please review the details by clicking the \"Go to Control\" button below.\n" +
                                    "\n" +
                                    "Thank you,\n" +
                                    "\n" +
                                    "Kind regards,\n" +
                                    "SoQM Team",
                        "WORKFLOW_STEP"
                );
            case SOQM_TO_OPERATOR_RETURN:
                return new NotificationTemplate(
                        "Control sent back to Control Operator",
                        greeting + "\n" +
                                "\n" +
                                "The control has been reviewed by the SoQM Team and requires some adjustments.\n" +
                                "Please click the \"Go to Control\" button to view the comments and feedback for this control.\n" +
                                "\n" +
                                "Thank you.\n" +
                                "\n" +
                                "Kind regards,\n" +
                                "SoQM Team",
                        "WORKFLOW_STEP"
                );
            case SOQM_TO_OWNER:
                return new NotificationTemplate(
                        "Control sent to Process Owner",
                        greeting + "\n" +
                                "\n" +
                                "The control has been reviewed by the SoQM Head/Delegate. Please review the control by clicking the \"Go to Control\" button below.\n" +
                                "\n" +
                                "Thank you.\n" +
                                "\n" +
                                "Kind regards,\n" +
                                "SoQM Team",
                        "WORKFLOW_STEP"
                );
            case COMPLETED_ALL:
                return new NotificationTemplate(
                        "Control completed by Process Owner",
                        greeting + "\n" +
                                "\n" +
                                "Please note that the control has been successfully completed in the system.\n" +
                                "\n" +
                                "Kind regards,\n" +
                                "SoQM Team",
                        "WORKFLOW_STEP"
                );
            case REMINDER_1:
            case REMINDER_1_FORWARD:
            case REMINDER_1_OPEN:
            case REMINDER_2:
            case REMINDER_2_FORWARD:
            case REMINDER_2_OPEN:
                int day = resolveReminderDay(type, control != null ? control.getControlFrequency() : null);
                return new NotificationTemplate(
                        ControlNotificationText.dayReminderSubject(day, control),
                        ControlNotificationText.dayReminderBody(day, control, deadline, link),
                        "REMINDER"
                );
            case DEADLINE:
                return new NotificationTemplate(
                        "Control Deadline Notification",
                        greeting + "\n" +
                                "\n" +
                                "Our records indicate that the control deadline has passed.\n" +
                                "The following control remains incomplete: " + controlIdAndNameComma + "\n" +
                                "\n" +
                                "Please ensure this control is completed by the end of the day. Click the \"Go to Control\" button below to access and complete the control.\n" +
                                "\n" +
                                "If you have any questions or require assistance, please let us know.\n" +
                                "\n" +
                                "Thank you,\n" +
                                "\n" +
                                "Kind regards,\n" +
                                "SoQM Team",
                        "REMINDER"
                );
            default:
                throw new IllegalArgumentException("Unsupported template type: " + type);
        }
    }

    public NotificationTemplate renderReturnNotification(Control control,
                                                         String returnedByName,
                                                         String returnedToLabel,
                                                         String comment,
                                                         String notificationType) {
        String controlId = buildControlId(control);
        String subject = "Control Returned to " + formatReturnedToLabel(returnedToLabel);

        StringBuilder body = new StringBuilder();
        body.append("Control ID: ").append(controlId).append("\n");
        body.append("Returned by: ").append(formatReturnedBy(returnedByName)).append("\n");

        if (comment != null && !comment.isBlank()) {
            body.append("\n");
            body.append("Return Comment:\n");
            body.append(comment.trim()).append("\n");
        }

        return new NotificationTemplate(subject, body.toString().trim(), notificationType);
    }

    public String buildControlLink(Control control) {
        if (control == null) {
            return "";
        }
        String path = "/view-control/" + control.getId();
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            log.warn("app.base-url is not configured. Using relative link for control {}", control.getId());
            return path;
        }
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + path;
    }

    public String buildPerformanceCycleLink(Control control) {
        if (control == null) {
            return "";
        }
        String path = "/performance-cycle/" + control.getId();
        if (baseUrl == null || baseUrl.trim().isEmpty()) {
            return path;
        }
        String base = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
        return base + path;
    }

    private String buildControlLabel(Control control) {
        if (control == null) {
            return "Control";
        }
        String id = control.getControlId() != null ? control.getControlId().trim() : "";
        String description = control.getControlDescription() != null ? control.getControlDescription().trim() : "";
        if (!id.isEmpty() && !description.isEmpty()) {
            return id + " - " + description;
        }
        if (!id.isEmpty()) {
            return id;
        }
        if (!description.isEmpty()) {
            return description;
        }
        return "Control";
    }

    private String buildControlId(Control control) {
        if (control == null) {
            return "Control";
        }
        String id = control.getControlId() != null ? control.getControlId().trim() : "";
        return id.isEmpty() ? "Control" : id;
    }

    private int resolveReminderDay(TemplateType type, String frequency) {
        if (type == null) {
            return 0;
        }
        boolean firstReminder = switch (type) {
            case REMINDER_1, REMINDER_1_FORWARD, REMINDER_1_OPEN -> true;
            default -> false;
        };
        String normalized = frequency == null ? "" : frequency.trim().toLowerCase();
        boolean monthly = normalized.contains("month");
        boolean annual = normalized.contains("annual");
        boolean semiAnnual = normalized.contains("semi");

        if (firstReminder) {
            return monthly ? 3 : 5;
        }
        if (monthly) {
            return 6;
        }
        if (annual || semiAnnual) {
            return 25;
        }
        return 12;
    }

    private String buildControlName(Control control) {
        if (control == null) {
            return "";
        }
        String description = control.getControlDescription() != null ? control.getControlDescription().trim() : "";
        return description;
    }

    private String buildControlIdAndName(String controlId, String controlName, String delimiter) {
        String id = controlId != null ? controlId.trim() : "";
        String name = controlName != null ? controlName.trim() : "";
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

    private String formatDeadline(LocalDate deadline) {
        if (deadline == null) {
            return "N/A";
        }
        return deadline.format(DEADLINE_FORMAT);
    }

    private String buildGreeting(String displayName) {
        String name = displayName != null ? displayName.trim() : "";
        if (!name.isEmpty()) {
            return "Dear " + name + ",";
        }
        return "Dear User,";
    }

    private String formatReturnedBy(String returnedByName) {
        String value = returnedByName != null ? returnedByName.trim() : "";
        return value.isEmpty() ? "User" : value;
    }

    private String formatReturnedToLabel(String returnedToLabel) {
        String value = returnedToLabel != null ? returnedToLabel.trim() : "";
        return value.isEmpty() ? "User" : value;
    }

    @Getter
    public static class NotificationTemplate {
        private final String subject;
        private final String body;
        private final String notificationType;

        public NotificationTemplate(String subject, String body, String notificationType) {
            this.subject = subject;
            this.body = body;
            this.notificationType = notificationType;
        }
    }

    public enum TemplateType {
        ACTIVATION,
        FACILITATOR_TO_OPERATOR,
        OPERATOR_TO_SOQM,
        SOQM_TO_OPERATOR_RETURN,
        SOQM_TO_OWNER,
        COMPLETED_ALL,
        REMINDER_1,
        REMINDER_1_FORWARD,
        REMINDER_1_OPEN,
        REMINDER_2,
        REMINDER_2_FORWARD,
        REMINDER_2_OPEN,
        DEADLINE
    }
}
