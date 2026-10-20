package com.kpmg.qtracker.scheduler;

import com.kpmg.qtracker.service.notification.PlannedNotificationScheduler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the planned notifications every 5 minutes on working days (PlannedNotificationScheduler starts at 09:30
 * Asia/Almaty); NOTIFICATIONS_SCHEDULER_ENABLED=false turns them off.
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "notifications.scheduler", name = "enabled", havingValue = "true", matchIfMissing = true)
public class PlannedNotificationTrigger {

    private final PlannedNotificationScheduler scheduler;

    @Scheduled(cron = "0 */5 * * * MON-FRI", zone = "Asia/Almaty")
    public void run() {
        try {
            int queued = scheduler.runOnce();
            if (queued > 0) {
                log.info("Planned notifications: {} e-mail(s) queued", queued);
            }
        } catch (RuntimeException e) {
            log.error("Planned notifications run failed", e);
        }
    }
}
