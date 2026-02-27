package com.kpmg.qtracker.scheduler;

import com.kpmg.qtracker.service.AnnualNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AnnualNotificationScheduler {

    private final AnnualNotificationService annualNotificationService;

    @Scheduled(cron = "0 30 09 * * *", zone = "Asia/Almaty")
    public void runAnnualNotifications() {
        runDay0();
        runDay25();
        runOverdue();
    }

    private void runDay0() {
        try {
            log.info("{ANNUAL DAY_0} Scheduler trigger");
            AnnualNotificationService.Day0RunSummary summary = annualNotificationService.sendDay0();
            log.info("{ANNUAL DAY_0} Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                    summary.getProcessedControlsCount(),
                    summary.getSentCount(),
                    summary.getDedupedCount(),
                    summary.getSkippedCount());
        } catch (Exception ex) {
            log.error("{ANNUAL DAY_0} Scheduler failed", ex);
        }
    }

    private void runDay25() {
        try {
            log.info("{ANNUAL DAY_25} Scheduler trigger");
            AnnualNotificationService.DayReminderRunSummary summary = annualNotificationService.sendDay25();
            log.info("{ANNUAL DAY_25} Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                    summary.getProcessedControlsCount(),
                    summary.getSentCount(),
                    summary.getDedupedCount(),
                    summary.getSkippedCount());
        } catch (Exception ex) {
            log.error("{ANNUAL DAY_25} Scheduler failed", ex);
        }
    }

    private void runOverdue() {
        try {
            log.info("{ANNUAL OVERDUE} Scheduler trigger");
            AnnualNotificationService.OverdueRunSummary summary = annualNotificationService.sendOverdue();
            log.info("{ANNUAL OVERDUE} Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                    summary.getProcessedControlsCount(),
                    summary.getSentCount(),
                    summary.getDedupedCount(),
                    summary.getSkippedCount());
        } catch (Exception ex) {
            log.error("{ANNUAL OVERDUE} Scheduler failed", ex);
        }
    }
}
