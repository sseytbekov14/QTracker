package com.kpmg.qtracker.scheduler;

import com.kpmg.qtracker.service.AnnualSemiDay0NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AnnualSemiDay0NotificationScheduler {

    private final AnnualSemiDay0NotificationService annualSemiDay0NotificationService;

    @Scheduled(cron = "0 30 09 * * *", zone = "Asia/Almaty")
    public void runDay0Notifications() {
        log.info("Scheduler trigger: annual/semi day0 notifications");
        AnnualSemiDay0NotificationService.Day0RunSummary summary =
                annualSemiDay0NotificationService.runDailyDay0Notifications();
        log.info("Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                summary.getProcessedControlsCount(),
                summary.getSentCount(),
                summary.getDedupedCount(),
                summary.getSkippedCount());
    }
}
