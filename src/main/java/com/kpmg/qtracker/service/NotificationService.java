package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.repository.NotificationRepository;
import com.kpmg.qtracker.repository.UserRepository;
import com.kpmg.qtracker.util.StatusDisplayMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {
    
    private final NotificationRepository notificationRepository;
    private final UserRepository userRepository;
    private final NotificationTemplateService notificationTemplateService;
    private final ObjectProvider<EmailNotificationChannel> emailNotificationChannelProvider;
    private final StatusDisplayMapper statusDisplayMapper;
    private static final String TYPE_AUTO_CREATED = "CONTROL_AUTO_CREATED";
    private static final String TYPE_INITIATE = "INITIATE";
    private static final long RETURN_DEDUPE_WINDOW_MINUTES = 5;
    private static final java.time.format.DateTimeFormatter AUTO_CREATE_DATE_FORMAT =
            java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy");
    
    /**
     * Send notification to specific users about control initiation
     */
    @Transactional
    public void sendInitiateNotifications(Control control, List<String> recipientEmails) {
        if (control == null || recipientEmails == null || recipientEmails.isEmpty()) {
            return;
        }
        if (control.getId() != null
                && notificationRepository.existsByControlIdAndType(control.getId(), TYPE_INITIATE)) {
            return;
        }
        sendTemplateNotifications(control, recipientEmails, NotificationTemplateService.TemplateType.ACTIVATION, false);
    }
    
    /**
     * Send notification about workflow step change
     */
    @Transactional
    public void sendWorkflowStepNotification(Control control, String recipientEmail, String stepName, String message) {
        if (control == null || recipientEmail == null || recipientEmail.isBlank()) {
            return;
        }
        NotificationTemplateService.NotificationTemplate template =
                new NotificationTemplateService.NotificationTemplate(
                        "Control " + control.getControlId() + " - " + stepName,
                        message,
                        "WORKFLOW_STEP");
        createNotification(recipientEmail, control, template);
    }
    
    /**
     * Send notifications to multiple users about workflow step
     */
    @Transactional
    public void sendWorkflowStepNotifications(Control control, List<String> recipientEmails, String stepName, String message) {
        if (control == null || recipientEmails == null || recipientEmails.isEmpty()) {
            return;
        }
        for (String email : recipientEmails) {
            sendWorkflowStepNotification(control, email, stepName, message);
        }
    }
    
    /**
     * Send status change notification
     */
    @Transactional
    public void sendStatusChangeNotification(Control control, String recipientEmail, String oldStatus, String newStatus) {
        if (control == null || recipientEmail == null || recipientEmail.isBlank()) {
            return;
        }
        String displayOld = statusDisplayMapper.display(oldStatus);
        String displayNew = statusDisplayMapper.display(newStatus);
        NotificationTemplateService.NotificationTemplate template =
                new NotificationTemplateService.NotificationTemplate(
                        "Control " + control.getControlId() + " status changed",
                        "Status changed from '" + displayOld + "' to '" + displayNew + "'",
                        "STATUS_CHANGE");
        createNotification(recipientEmail, control, template);
    }

    @Transactional
    public void sendAutoCreatedNotification(Control control,
                                            String recipientEmail,
                                            String frequencyLabel,
                                            java.time.LocalDate operationDate) {
        if (control == null || recipientEmail == null || recipientEmail.isBlank() || operationDate == null) {
            return;
        }
        if (notificationRepository.existsByControlIdAndType(control.getId(), TYPE_AUTO_CREATED)) {
            return;
        }
        String controlName = control.getControlId() != null ? control.getControlId() : "Control";
        String frequency = (frequencyLabel == null || frequencyLabel.isBlank())
                ? "scheduled"
                : frequencyLabel.trim();
        String dateText = operationDate.format(AUTO_CREATE_DATE_FORMAT);
        NotificationTemplateService.NotificationTemplate template =
                new NotificationTemplateService.NotificationTemplate(
                        "Auto-created control: " + controlName,
                        "A new " + frequency + " control occurrence was created automatically for " + dateText
                                + ". Please review and initiate.",
                        TYPE_AUTO_CREATED
                );
        createNotification(recipientEmail, control, template);
    }

    @Transactional
    public void sendTemplateNotifications(Control control,
                                          List<String> recipientEmails,
                                          NotificationTemplateService.TemplateType templateType,
                                          boolean resubmitted) {
        if (control == null || recipientEmails == null || recipientEmails.isEmpty()) {
            return;
        }
        Set<String> unique = new LinkedHashSet<>();
        for (String email : recipientEmails) {
            if (email != null && !email.isBlank()) {
                unique.add(email.trim());
            }
        }
        for (String email : unique) {
            createNotification(email, control, templateType, resubmitted);
        }
    }

    @Transactional
    public void sendReturnNotifications(Control control,
                                        List<String> recipientEmails,
                                        String performedByRole,
                                        String performedByName,
                                        String returnedToLabel,
                                        String returnComment,
                                        String notificationType) {
        if (control == null || recipientEmails == null || recipientEmails.isEmpty()) {
            return;
        }
        String actor = normalizeActorName(performedByName, performedByRole);
        NotificationTemplateService.NotificationTemplate template =
                notificationTemplateService.renderReturnNotification(
                        control,
                        actor,
                        returnedToLabel,
                        returnComment,
                        notificationType
                );

        Set<String> unique = new LinkedHashSet<>();
        for (String email : recipientEmails) {
            if (email != null && !email.isBlank()) {
                unique.add(email.trim());
            }
        }
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime start = now.minusMinutes(RETURN_DEDUPE_WINDOW_MINUTES);
        LocalDateTime end = now.plusSeconds(1);

        for (String email : unique) {
            userRepository.findByMail(email).ifPresent(user -> {
                boolean alreadySent = notificationRepository
                        .existsByControlIdAndUserIdAndTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
                                control.getId(),
                                user.getId(),
                                notificationType,
                                start,
                                end
                        );
                if (alreadySent) {
                    return;
                }

                Notification notif = new Notification();
                notif.setUserId(user.getId());
                notif.setControlId(control.getId());
                notif.setType(template.getNotificationType());
                notif.setTitle(template.getSubject());
                notif.setMessage(template.getBody());
                notif.setLink(notificationTemplateService.buildControlLink(control));
                notif.setIsRead(false);
                notificationRepository.save(notif);

                EmailNotificationChannel emailChannel = emailNotificationChannelProvider.getIfAvailable();
                if (emailChannel != null) {
                    emailChannel.send(email, template.getSubject(), template.getBody());
                }
            });
        }
    }

    private String normalizeActorName(String performedByName, String performedByRole) {
        String name = performedByName != null ? performedByName.trim() : "";
        if (!name.isEmpty()) {
            return name;
        }
        return mapRoleLabel(performedByRole);
    }

    private void createNotification(String email,
                                    Control control,
                                    NotificationTemplateService.TemplateType templateType,
                                    boolean resubmitted) {
        if (email == null || email.isBlank()) {
            return;
        }
        userRepository.findByMail(email).ifPresent(user -> {
            String roleLabel = mapRoleLabel(user.getRole());
            LocalDate templateDate = resolveTemplateDate(control, templateType);
            NotificationTemplateService.NotificationTemplate template =
                    notificationTemplateService.render(
                            templateType,
                            control,
                            templateDate,
                            resubmitted,
                            user.getDisplayName(),
                            roleLabel
                    );
            String submitTitle = buildSubmitTitle(templateType);
            Notification notif = new Notification();
            notif.setUserId(user.getId());
            notif.setControlId(control.getId());
            notif.setType(template.getNotificationType());
            notif.setTitle(submitTitle != null ? submitTitle : template.getSubject());
            notif.setMessage(template.getBody());
            notif.setLink(notificationTemplateService.buildControlLink(control));
            notif.setIsRead(false);
            notificationRepository.save(notif);
            EmailNotificationChannel emailChannel = emailNotificationChannelProvider.getIfAvailable();
            if (emailChannel != null) {
                emailChannel.send(email, template.getSubject(), template.getBody());
            }
            log.debug("Notification created: controlId={}, userId={}, email={}, role={}, templateType={}, subject={}",
                    control.getId(),
                    user.getId(),
                    email,
                    user.getRole(),
                    templateType,
                    template.getSubject());
        });
    }

    private void createNotification(String email,
                                    Control control,
                                    NotificationTemplateService.NotificationTemplate template) {
        if (email == null || email.isBlank() || template == null) {
            return;
        }
        userRepository.findByMail(email).ifPresent(user -> {
            Notification notif = new Notification();
            notif.setUserId(user.getId());
            notif.setControlId(control.getId());
            notif.setType(template.getNotificationType());
            notif.setTitle(template.getSubject());
            notif.setMessage(template.getBody());
            notif.setLink(notificationTemplateService.buildControlLink(control));
            notif.setIsRead(false);
            notificationRepository.save(notif);
            EmailNotificationChannel emailChannel = emailNotificationChannelProvider.getIfAvailable();
            if (emailChannel != null) {
                emailChannel.send(email, template.getSubject(), template.getBody());
            }
            log.debug("Notification created: controlId={}, userId={}, email={}, role={}, templateType={}, subject={}",
                    control.getId(),
                    user.getId(),
                    email,
                    user.getRole(),
                    template.getNotificationType(),
                    template.getSubject());
        });
    }

    private String mapRoleLabel(String role) {
        if (role == null) {
            return "User";
        }
        switch (role) {
            case "FACILITATOR":
                return "Facilitator";
            case "CONTROL_OPERATOR":
                return "Control Operator";
            case "SOQM_TEAM":
                return "SoQM Head/Delegate";
            case "PROCESS_OWNER":
                return "Process Owner";
            case "ADMIN":
                return "Admin";
            default:
                return "User";
        }
    }

    private String buildSubmitTitle(NotificationTemplateService.TemplateType templateType) {
        if (templateType == null) {
            return null;
        }
        switch (templateType) {
            case FACILITATOR_TO_OPERATOR:
                return "Control Submitted by Facilitator";
            case OPERATOR_TO_SOQM:
                return "Control Submitted by Control Operator";
            case SOQM_TO_OWNER:
                return "Control Submitted by SoQM Head";
            default:
                return null;
        }
    }

    private LocalDate resolveTemplateDate(Control control, NotificationTemplateService.TemplateType templateType) {
        if (control == null) {
            return null;
        }
        if (templateType == null) {
            return control.getDeadline();
        }
        switch (templateType) {
            case REMINDER_1:
            case REMINDER_1_FORWARD:
            case REMINDER_1_OPEN:
            case REMINDER_2:
            case REMINDER_2_FORWARD:
            case REMINDER_2_OPEN:
                if (control.getControlOperationDate() != null) {
                    return control.getControlOperationDate();
                }
                return control.getDeadline();
            default:
                return control.getDeadline();
        }
    }
    
    /**
     * Send notification when a control is shared with a user
     */
    @Transactional
    public void sendSharedWithNotification(Control control, String recipientEmail, String sharedByDisplayName) {
        if (control == null || recipientEmail == null || recipientEmail.isBlank()) {
            return;
        }
        String controlName = control.getControlId() != null ? control.getControlId() : "Control";
        String title = "Control " + controlName + " shared with you";
        String message = sharedByDisplayName + " shared control " + controlName + " with you (view-only).";

        userRepository.findByMail(recipientEmail).ifPresent(user -> {
            Notification notif = new Notification();
            notif.setUserId(user.getId());
            notif.setControlId(control.getId());
            notif.setType("CONTROL_SHARED");
            notif.setTitle(title);
            notif.setMessage(message);
            notif.setLink(notificationTemplateService.buildPerformanceCycleLink(control));
            notif.setIsRead(false);
            notificationRepository.save(notif);
            EmailNotificationChannel emailChannel = emailNotificationChannelProvider.getIfAvailable();
            if (emailChannel != null) {
                emailChannel.send(recipientEmail, title, message);
            }
        });
    }

    /**
     * Get all notifications for a user
     */
    public List<Notification> getUserNotifications(Long userId) {
        return notificationRepository.findByUserIdOrderByCreatedAtDesc(userId);
    }
    
    /**
     * Get unread notifications for a user
     */
    public List<Notification> getUnreadNotifications(Long userId) {
        return notificationRepository.findByUserIdAndIsReadFalseOrderByCreatedAtDesc(userId);
    }
    
    /**
     * Count unread notifications for a user
     */
    public long countUnread(Long userId) {
        return notificationRepository.countByUserIdAndIsReadFalse(userId);
    }
    
    /**
     * Mark notification as read
     */
    @Transactional
    public void markAsRead(Long notificationId) {
        notificationRepository.findById(notificationId).ifPresent(notif -> {
            if (!notif.getIsRead()) {
                notif.setIsRead(true);
                notif.setReadAt(LocalDateTime.now());
                notificationRepository.save(notif);
                log.debug("Marked notification {} as read", notificationId);
            }
        });
    }
    
    /**
     * Mark all notifications as read for a user
     */
    @Transactional
    public int markAllAsRead(Long userId) {
        int updated = notificationRepository.markAllAsReadForUser(userId);
        log.info("Marked {} notifications as read for user {}", updated, userId);
        return updated;
    }
}
