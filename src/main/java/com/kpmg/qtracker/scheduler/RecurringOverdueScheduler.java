package com.kpmg.qtracker.scheduler;

import com.kpmg.qtracker.service.RecurringOverdueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class RecurringOverdueScheduler {

    private final RecurringOverdueService recurringOverdueService;

    @Scheduled(cron = "0 28 10 * * *", zone = "Asia/Almaty")
    public void runRecurringOverdues() {
        log.info("Scheduler trigger: recurring overdue reminders");
        RecurringOverdueService.OverdueRunSummary summary = recurringOverdueService.runDailyOverdues();
        log.info("Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                summary.getProcessedControlsCount(),
                summary.getSentCount(),
                summary.getDedupedCount(),
                summary.getSkippedCount());
    }
}
