package com.kpmg.qtracker.scheduler;

import com.kpmg.qtracker.service.MonthlyDay0NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class MonthlyDay0NotificationScheduler {
    private final MonthlyDay0NotificationService monthlyDay0NotificationService;

    @Scheduled(cron = "0 35 16 * * *", zone = "Asia/Almaty")
    public void runDay0Notifications() {
        log.info("Scheduler trigger: monthly day0 notifications");
        MonthlyDay0NotificationService.Day0RunSummary summary =
                monthlyDay0NotificationService.runDailyDay0Notifications();
        log.info("Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                summary.getProcessedControlsCount(),
                summary.getSentCount(),
                summary.getDedupedCount(),
                summary.getSkippedCount());
    }
}