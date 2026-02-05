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
        String controlLabel = buildControlLabel(control);
        String link = buildControlLink(control);
        String deadlineText = formatDeadline(deadline);
        String greeting = buildGreeting(recipientRole);

        switch (type) {
            case ACTIVATION:
                return new NotificationTemplate(
                        "Control Initiated",
                        greeting + "\n" +
                        "\n" +
                                "The scheduled control " + controlLabel + " has been activated today. Kindly complete and submit the control as required before the deadline " + deadlineText + ".\n" +
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
                                "The control has been completed by the Facilitator. Please revise and review the control by accessing the control using the link:\n" +
                                link + "\n" +
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
                                    "The control has been adjusted by the Control Operator. Please click the link below to view the control\n" +
                                    link + "\n" +
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
                                    "The control has been completed by the Control Operator. Please revise and review the control by accessing the control using the link:\n" +
                                    link + "\n" +
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
                                "The control has been reviewed by the SoQM Team and requires some adjustments\n" +
                                "Please click the link below to view the comments and feedback for this control:\n" +
                                link + "\n" +
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
                                "The control has been reviewed by the SoQM Head/Delegate. Please revise and review the control by accessing the control using the link: \n" +
                                link + "\n" +
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
                return new NotificationTemplate(
                        "Control Reminder",
                        greeting + "\n" +
                                "We kindly remind you to complete the control " + controlLabel + ". You can access the control using the following link " + link + ". The deadline of the control is on " + deadlineText + "\n" +
                                "Thank you,\n" +
                                "Kind regards,\n" +
                                "SoQM Team",
                        "REMINDER"
                );
            case REMINDER_1_FORWARD:
                return new NotificationTemplate(
                        "Control Reminder",
                        greeting + "\n" +
                                "We kindly remind you to submit the control " + controlLabel + " to the next reviewer. You can access the control using the following link " + link + ". The deadline of the control is on " + deadlineText + "\n" +
                                "Thank you,\n" +
                                "Kind regards,\n" +
                                "SoQM Team",
                        "REMINDER"
                );
            case REMINDER_1_OPEN:
                return new NotificationTemplate(
                        "Control Reminder",
                        greeting + "\n" +
                                "We kindly remind you that the control " + controlLabel + " is still open. You can access the control using the following link " + link + ". The deadline of the control is on " + deadlineText + "\n" +
                                "Thank you,\n" +
                                "Kind regards,\n" +
                                "SoQM Team",
                        "REMINDER"
                );
            case REMINDER_2:
                return new NotificationTemplate(
                        "Control Reminder",
                        greeting + "\n" +
                                "Control " + controlLabel + " deadline is approaching. You can access the control using the following link " + link + ". The deadline of the control is on " + deadlineText + "\n" +
                                "Thank you,\n" +
                                "Kind regards,\n" +
                                "SoQM Team",
                        "REMINDER"
                );
            case REMINDER_2_FORWARD:
                return new NotificationTemplate(
                        "Control Reminder",
                        greeting + "\n" +
                                "Control " + controlLabel + " deadline is approaching. Please submit it to the next reviewer using the following link " + link + ". The deadline of the control is on " + deadlineText + "\n" +
                                "Thank you,\n" +
                                "Kind regards,\n" +
                                "SoQM Team",
                        "REMINDER"
                );
            case REMINDER_2_OPEN:
                return new NotificationTemplate(
                        "Control Reminder",
                        greeting + "\n" +
                                "Control " + controlLabel + " is still open and the deadline is approaching. You can access the control using the following link " + link + ". The deadline of the control is on " + deadlineText + "\n" +
                                "Thank you,\n" +
                                "Kind regards,\n" +
                                "SoQM Team",
                        "REMINDER"
                );
            case DEADLINE:
                return new NotificationTemplate(
                        "Control Deadline Notification",
                        greeting + "\n" +
                                "Our records indicate that the control deadline has passed.\n" +
                                "The following control remains incomplete: " + controlLabel + "\n" +
                                "Please ensure this control is completed by the end of the day. You can access and complete the control using the link below:\n" +
                                link + "\n" +
                                "If you have any questions or require assistance, please let us know.\n" +
                                "Thank you,\n" +
                                "Kind regards,\n" +
                                "SoQM Team",
                        "REMINDER"
                );
            default:
                throw new IllegalArgumentException("Unsupported template type: " + type);
        }
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

    private String formatDeadline(LocalDate deadline) {
        if (deadline == null) {
            return "N/A";
        }
        return deadline.format(DEADLINE_FORMAT);
    }

    private String buildGreeting(String recipientRole) {
        String role = recipientRole != null ? recipientRole.trim() : "";
        if (!role.isEmpty()) {
            return "Dear " + role + ",";
        }
        return "Dear User,";
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
