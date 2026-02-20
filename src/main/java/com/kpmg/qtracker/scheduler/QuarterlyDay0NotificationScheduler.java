package com.kpmg.qtracker.scheduler;

import com.kpmg.qtracker.service.QuarterlyDay0NotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class QuarterlyDay0NotificationScheduler {

    private final QuarterlyDay0NotificationService quarterlyDay0NotificationService;

    @Scheduled(cron = "0 04 16 * * *", zone = "Asia/Almaty")
    public void runDay0Notifications() {
        log.info("Scheduler trigger: quarterly day0 notifications");
        QuarterlyDay0NotificationService.Day0RunSummary summary =
                quarterlyDay0NotificationService.runDailyDay0Notifications();
        log.info("Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                summary.getProcessedControlsCount(),
                summary.getSentCount(),
                summary.getDedupedCount(),
                summary.getSkippedCount());
    }
}
