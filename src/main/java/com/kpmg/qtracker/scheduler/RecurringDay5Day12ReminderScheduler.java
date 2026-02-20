package com.kpmg.qtracker.scheduler;

import com.kpmg.qtracker.service.RecurringDay5Day12ReminderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class RecurringDay5Day12ReminderScheduler {

    private final RecurringDay5Day12ReminderService recurringDay5Day12ReminderService;

    @Scheduled(cron = "0 04 10 * * *", zone = "Asia/Almaty")
    public void runRecurringDayReminders() {
        log.info("Scheduler trigger: recurring day5/day12 reminders");
        RecurringDay5Day12ReminderService.DayReminderRunSummary summary =
                recurringDay5Day12ReminderService.runDailyReminders();
        log.info("Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                summary.getProcessedControlsCount(),
                summary.getSentCount(),
                summary.getDedupedCount(),
                summary.getSkippedCount());
    }
}
