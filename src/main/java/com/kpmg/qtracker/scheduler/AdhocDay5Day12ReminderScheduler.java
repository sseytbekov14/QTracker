package com.kpmg.qtracker.scheduler;

import com.kpmg.qtracker.service.AdhocDay5Day12ReminderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AdhocDay5Day12ReminderScheduler {

    private final AdhocDay5Day12ReminderService adhocDay5Day12ReminderService;

    @Scheduled(cron = "0 30 09 * * *", zone = "Asia/Almaty")
    public void runAdhocDayReminders() {
        log.info("Scheduler trigger: ad-hoc day5/day12 reminders");
        AdhocDay5Day12ReminderService.DayReminderRunSummary summary =
                adhocDay5Day12ReminderService.runDailyReminders();
        log.info("Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                summary.getProcessedControlsCount(),
                summary.getSentCount(),
                summary.getDedupedCount(),
                summary.getSkippedCount());
    }
}
