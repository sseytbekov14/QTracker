package com.kpmg.qtracker.scheduler;

import com.kpmg.qtracker.service.AdhocNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AdhocNotificationScheduler {

    private final AdhocNotificationService adhocNotificationService;

    @Scheduled(cron = "0 55 15 * * *", zone = "Asia/Almaty")
    public void runAdhocNotifications() {
        runDay0();
        runDay5();
        runDay12();
        runOverdue();
    }

    private void runDay0() {
        try {
            log.info("{ADHOC DAY_0} Scheduler trigger");
            AdhocNotificationService.Day0RunSummary summary = adhocNotificationService.sendDay0();
            log.info("{ADHOC DAY_0} Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                    summary.getProcessedControlsCount(),
                    summary.getSentCount(),
                    summary.getDedupedCount(),
                    summary.getSkippedCount());
        } catch (Exception ex) {
            log.error("{ADHOC DAY_0} Scheduler failed", ex);
        }
    }

    private void runDay5() {
        try {
            log.info("{ADHOC DAY_5} Scheduler trigger");
            AdhocNotificationService.DayReminderRunSummary summary = adhocNotificationService.sendDay5();
            log.info("{ADHOC DAY_5} Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                    summary.getProcessedControlsCount(),
                    summary.getSentCount(),
                    summary.getDedupedCount(),
                    summary.getSkippedCount());
        } catch (Exception ex) {
            log.error("{ADHOC DAY_5} Scheduler failed", ex);
        }
    }

    private void runDay12() {
        try {
            log.info("{ADHOC DAY_12} Scheduler trigger");
            AdhocNotificationService.DayReminderRunSummary summary = adhocNotificationService.sendDay12();
            log.info("{ADHOC DAY_12} Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                    summary.getProcessedControlsCount(),
                    summary.getSentCount(),
                    summary.getDedupedCount(),
                    summary.getSkippedCount());
        } catch (Exception ex) {
            log.error("{ADHOC DAY_12} Scheduler failed", ex);
        }
    }

    private void runOverdue() {
        try {
            log.info("{ADHOC OVERDUE} Scheduler trigger");
            AdhocNotificationService.OverdueRunSummary summary = adhocNotificationService.sendOverdue();
            log.info("{ADHOC OVERDUE} Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                    summary.getProcessedControlsCount(),
                    summary.getSentCount(),
                    summary.getDedupedCount(),
                    summary.getSkippedCount());
        } catch (Exception ex) {
            log.error("{ADHOC OVERDUE} Scheduler failed", ex);
        }
    }
}
