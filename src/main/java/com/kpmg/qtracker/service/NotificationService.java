package com.kpmg.qtracker.service;

import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.repository.NotificationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * The in-app notifications of a user, for the Notifications section: reading them and marking them read. They are made
 * by NotificationProducer only, with their e-mail (service.notification).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class NotificationService {

    private final NotificationRepository notificationRepository;

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
                notif.setReadAt(LocalDateTime.now(Notification.ZONE));
                notificationRepository.save(notif);
                log.debug("Marked notification {} as read", notificationId);
            }
        });
    }
    
    /**
     * The notification if it belongs to the user; empty when it does not exist or is someone else's
     */
    public Optional<Notification> findForUser(Long notificationId, Long userId) {
        return notificationRepository.findById(notificationId)
                .filter(notif -> notif.getUserId() != null && notif.getUserId().equals(userId));
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
