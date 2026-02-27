package com.kpmg.qtracker.scheduler;

import com.kpmg.qtracker.service.MonthlyNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class MonthlyNotificationScheduler {

    private final MonthlyNotificationService monthlyNotificationService;

    @Scheduled(cron = "0 54 14 * * *", zone = "Asia/Almaty")
    public void runMonthlyNotifications() {
        runDay0();
        runDay3();
        runDay6();
        runOverdue();
    }

    private void runDay0() {
        try {
            log.info("{MONTHLY DAY_0} Scheduler trigger");
            MonthlyNotificationService.Day0RunSummary summary = monthlyNotificationService.sendDay0();
            log.info("{MONTHLY DAY_0} Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                    summary.getProcessedControlsCount(),
                    summary.getSentCount(),
                    summary.getDedupedCount(),
                    summary.getSkippedCount());
        } catch (Exception ex) {
            log.error("{MONTHLY DAY_0} Scheduler failed", ex);
        }
    }

    private void runDay3() {
        try {
            log.info("{MONTHLY DAY_3} Scheduler trigger");
            MonthlyNotificationService.DayReminderRunSummary summary = monthlyNotificationService.sendDay3();
            log.info("{MONTHLY DAY_3} Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                    summary.getProcessedControlsCount(),
                    summary.getSentCount(),
                    summary.getDedupedCount(),
                    summary.getSkippedCount());
        } catch (Exception ex) {
            log.error("{MONTHLY DAY_3} Scheduler failed", ex);
        }
    }

    private void runDay6() {
        try {
            log.info("{MONTHLY DAY_6} Scheduler trigger");
            MonthlyNotificationService.DayReminderRunSummary summary = monthlyNotificationService.sendDay6();
            log.info("{MONTHLY DAY_6} Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                    summary.getProcessedControlsCount(),
                    summary.getSentCount(),
                    summary.getDedupedCount(),
                    summary.getSkippedCount());
        } catch (Exception ex) {
            log.error("{MONTHLY DAY_6} Scheduler failed", ex);
        }
    }

    private void runOverdue() {
        try {
            log.info("{MONTHLY OVERDUE} Scheduler trigger");
            MonthlyNotificationService.OverdueRunSummary summary = monthlyNotificationService.sendOverdue();
            log.info("{MONTHLY OVERDUE} Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                    summary.getProcessedControlsCount(),
                    summary.getSentCount(),
                    summary.getDedupedCount(),
                    summary.getSkippedCount());
        } catch (Exception ex) {
            log.error("{MONTHLY OVERDUE} Scheduler failed", ex);
        }
    }
}
