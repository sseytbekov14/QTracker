package com.kpmg.qtracker.scheduler;

import com.kpmg.qtracker.service.MonthlyDay3Day6ReminderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class MonthlyDay3Day6ReminderScheduler {

    private final MonthlyDay3Day6ReminderService monthlyDay3Day6ReminderService;

    @Scheduled(cron = "0 30 09 * * *", zone = "Asia/Almaty")
    public void runMonthlyDayReminders() {
        log.info("Scheduler trigger: monthly day3/day6 reminders");
        MonthlyDay3Day6ReminderService.DayReminderRunSummary summary =
                monthlyDay3Day6ReminderService.runDailyReminders();
        log.info("Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                summary.getProcessedControlsCount(),
                summary.getSentCount(),
                summary.getDedupedCount(),
                summary.getSkippedCount());
    }
}
