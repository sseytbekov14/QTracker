package com.kpmg.qtracker.scheduler;

import com.kpmg.qtracker.service.RecurringNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class RecurringNotificationScheduler {

    private final RecurringNotificationService recurringNotificationService;

    @Scheduled(cron = "0 55 15 * * *", zone = "Asia/Almaty")
    public void runRecurringNotifications() {
        runDay0();
        runDay5();
        runDay12();
        runOverdue();
    }

    private void runDay0() {
        try {
            log.info("{RECURRING DAY_0} Scheduler trigger");
            RecurringNotificationService.Day0RunSummary summary = recurringNotificationService.sendDay0();
            log.info("{RECURRING DAY_0} Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                    summary.getProcessedControlsCount(),
                    summary.getSentCount(),
                    summary.getDedupedCount(),
                    summary.getSkippedCount());
        } catch (Exception ex) {
            log.error("{RECURRING DAY_0} Scheduler failed", ex);
        }
    }

    private void runDay5() {
        try {
            log.info("{RECURRING DAY_5} Scheduler trigger");
            RecurringNotificationService.DayReminderRunSummary summary = recurringNotificationService.sendDay5();
            log.info("{RECURRING DAY_5} Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                    summary.getProcessedControlsCount(),
                    summary.getSentCount(),
                    summary.getDedupedCount(),
                    summary.getSkippedCount());
        } catch (Exception ex) {
            log.error("{RECURRING DAY_5} Scheduler failed", ex);
        }
    }

    private void runDay12() {
        try {
            log.info("{RECURRING DAY_12} Scheduler trigger");
            RecurringNotificationService.DayReminderRunSummary summary = recurringNotificationService.sendDay12();
            log.info("{RECURRING DAY_12} Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                    summary.getProcessedControlsCount(),
                    summary.getSentCount(),
                    summary.getDedupedCount(),
                    summary.getSkippedCount());
        } catch (Exception ex) {
            log.error("{RECURRING DAY_12} Scheduler failed", ex);
        }
    }

    private void runOverdue() {
        try {
            log.info("{RECURRING OVERDUE} Scheduler trigger");
            RecurringNotificationService.OverdueRunSummary summary = recurringNotificationService.sendOverdue();
            log.info("{RECURRING OVERDUE} Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                    summary.getProcessedControlsCount(),
                    summary.getSentCount(),
                    summary.getDedupedCount(),
                    summary.getSkippedCount());
        } catch (Exception ex) {
            log.error("{RECURRING OVERDUE} Scheduler failed", ex);
        }
    }
}
