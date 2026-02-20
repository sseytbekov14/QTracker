package com.kpmg.qtracker.scheduler;

import com.kpmg.qtracker.service.RecurringDay0NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class RecurringDay0NotificationScheduler {

    private final RecurringDay0NotificationService recurringDay0NotificationService;

    @Scheduled(cron = "0 04 10 * * *", zone = "Asia/Almaty")
    public void runDay0Notifications() {
        log.info("Scheduler trigger: recurring day0 notifications");
        RecurringDay0NotificationService.Day0RunSummary summary =
                recurringDay0NotificationService.runDailyDay0Notifications();
        log.info("Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                summary.getProcessedControlsCount(),
                summary.getSentCount(),
                summary.getDedupedCount(),
                summary.getSkippedCount());
    }
}
