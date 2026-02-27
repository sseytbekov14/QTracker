package com.kpmg.qtracker.scheduler;

import com.kpmg.qtracker.service.QuarterlyNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class QuarterlyNotificationScheduler {

    private final QuarterlyNotificationService quarterlyNotificationService;

    @Scheduled(cron = "0 54 14 * * *", zone = "Asia/Almaty")
    public void runQuarterlyNotifications() {
        runDay0();
        runDay5();
        runDay12();
        runOverdue();
    }

    private void runDay0() {
        try {
            log.info("{QUARTERLY DAY_0} Scheduler trigger");
            QuarterlyNotificationService.Day0RunSummary summary = quarterlyNotificationService.sendDay0();
            log.info("{QUARTERLY DAY_0} Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                    summary.getProcessedControlsCount(),
                    summary.getSentCount(),
                    summary.getDedupedCount(),
                    summary.getSkippedCount());
        } catch (Exception ex) {
            log.error("{QUARTERLY DAY_0} Scheduler failed", ex);
        }
    }

    private void runDay5() {
        try {
            log.info("{QUARTERLY DAY_5} Scheduler trigger");
            QuarterlyNotificationService.DayReminderRunSummary summary = quarterlyNotificationService.sendDay5();
            log.info("{QUARTERLY DAY_5} Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                    summary.getProcessedControlsCount(),
                    summary.getSentCount(),
                    summary.getDedupedCount(),
                    summary.getSkippedCount());
        } catch (Exception ex) {
            log.error("{QUARTERLY DAY_5} Scheduler failed", ex);
        }
    }

    private void runDay12() {
        try {
            log.info("{QUARTERLY DAY_12} Scheduler trigger");
            QuarterlyNotificationService.DayReminderRunSummary summary = quarterlyNotificationService.sendDay12();
            log.info("{QUARTERLY DAY_12} Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                    summary.getProcessedControlsCount(),
                    summary.getSentCount(),
                    summary.getDedupedCount(),
                    summary.getSkippedCount());
        } catch (Exception ex) {
            log.error("{QUARTERLY DAY_12} Scheduler failed", ex);
        }
    }

    private void runOverdue() {
        try {
            log.info("{QUARTERLY OVERDUE} Scheduler trigger");
            QuarterlyNotificationService.OverdueRunSummary summary = quarterlyNotificationService.sendOverdue();
            log.info("{QUARTERLY OVERDUE} Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                    summary.getProcessedControlsCount(),
                    summary.getSentCount(),
                    summary.getDedupedCount(),
                    summary.getSkippedCount());
        } catch (Exception ex) {
            log.error("{QUARTERLY OVERDUE} Scheduler failed", ex);
        }
    }
}
