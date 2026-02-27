package com.kpmg.qtracker.scheduler;

import com.kpmg.qtracker.service.SemiAnnualNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SemiAnnualNotificationScheduler {

    private final SemiAnnualNotificationService semiAnnualNotificationService;

    @Scheduled(cron = "0 01 16 * * *", zone = "Asia/Almaty")
    public void runSemiAnnualNotifications() {
        runDay0();
        runDay25();
        runOverdue();
    }

    private void runDay0() {
        try {
            log.info("{SEMI_ANNUAL DAY_0} Scheduler trigger");
            SemiAnnualNotificationService.Day0RunSummary summary = semiAnnualNotificationService.sendDay0();
            log.info("{SEMI_ANNUAL DAY_0} Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                    summary.getProcessedControlsCount(),
                    summary.getSentCount(),
                    summary.getDedupedCount(),
                    summary.getSkippedCount());
        } catch (Exception ex) {
            log.error("{SEMI_ANNUAL DAY_0} Scheduler failed", ex);
        }
    }

    private void runDay25() {
        try {
            log.info("{SEMI_ANNUAL DAY_25} Scheduler trigger");
            SemiAnnualNotificationService.DayReminderRunSummary summary = semiAnnualNotificationService.sendDay25();
            log.info("{SEMI_ANNUAL DAY_25} Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                    summary.getProcessedControlsCount(),
                    summary.getSentCount(),
                    summary.getDedupedCount(),
                    summary.getSkippedCount());
        } catch (Exception ex) {
            log.error("{SEMI_ANNUAL DAY_25} Scheduler failed", ex);
        }
    }

    private void runOverdue() {
        try {
            log.info("{SEMI_ANNUAL OVERDUE} Scheduler trigger");
            SemiAnnualNotificationService.OverdueRunSummary summary = semiAnnualNotificationService.sendOverdue();
            log.info("{SEMI_ANNUAL OVERDUE} Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                    summary.getProcessedControlsCount(),
                    summary.getSentCount(),
                    summary.getDedupedCount(),
                    summary.getSkippedCount());
        } catch (Exception ex) {
            log.error("{SEMI_ANNUAL OVERDUE} Scheduler failed", ex);
        }
    }
}
