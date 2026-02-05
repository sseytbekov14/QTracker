package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.repository.NotificationRepository;
import com.kpmg.qtracker.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

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
    
    /**
     * Send notification to specific users about control initiation
     */
    @Transactional
    public void sendInitiateNotifications(Control control, List<String> recipientEmails) {
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
        NotificationTemplateService.NotificationTemplate template =
                new NotificationTemplateService.NotificationTemplate(
                        "Control " + control.getControlId() + " status changed",
                        "Status changed from '" + oldStatus + "' to '" + newStatus + "'",
                        "STATUS_CHANGE");
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

    private void createNotification(String email,
                                    Control control,
                                    NotificationTemplateService.TemplateType templateType,
                                    boolean resubmitted) {
        if (email == null || email.isBlank()) {
            return;
        }
        userRepository.findByMail(email).ifPresent(user -> {
            String roleLabel = mapRoleLabel(user.getRole());
            NotificationTemplateService.NotificationTemplate template =
                    notificationTemplateService.render(
                            templateType,
                            control,
                            control.getDeadline(),
                            resubmitted,
                            user.getDisplayName(),
                            roleLabel
                    );
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
            log.debug("Created notification for user {}", email);
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
            log.debug("Created notification for user {}", email);
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
            case "SOQM_LEAD":
                return "SoQM Head/Delegate";
            case "PROCESS_OWNER":
                return "Process Owner";
            case "ADMIN":
                return "Admin";
            default:
                return "User";
        }
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
