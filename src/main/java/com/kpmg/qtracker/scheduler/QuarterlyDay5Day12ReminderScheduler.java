package com.kpmg.qtracker.scheduler;

import com.kpmg.qtracker.service.QuarterlyDay5Day12ReminderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class QuarterlyDay5Day12ReminderScheduler {

    private final QuarterlyDay5Day12ReminderService quarterlyDay5Day12ReminderService;

    @Scheduled(cron = "0 59 15 * * *", zone = "Asia/Almaty")
    public void runQuarterlyDayReminders() {
        log.info("Scheduler trigger: quarterly day5/day12 reminders");
        QuarterlyDay5Day12ReminderService.DayReminderRunSummary summary =
                quarterlyDay5Day12ReminderService.runDailyReminders();
        log.info("Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                summary.getProcessedControlsCount(),
                summary.getSentCount(),
                summary.getDedupedCount(),
                summary.getSkippedCount());
    }
}
