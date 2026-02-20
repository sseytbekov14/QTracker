package com.kpmg.qtracker.scheduler;

import com.kpmg.qtracker.service.AnnualSemiDay5Day25ReminderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AnnualSemiDay5Day25ReminderScheduler {

    private final AnnualSemiDay5Day25ReminderService annualSemiDay5Day25ReminderService;

    @Scheduled(cron = "0 30 09 * * *", zone = "Asia/Almaty")
    public void runAnnualSemiDayReminders() {
        log.info("Scheduler trigger: annual/semi day5/day25 reminders");
        AnnualSemiDay5Day25ReminderService.DayReminderRunSummary summary =
                annualSemiDay5Day25ReminderService.runDailyReminders();
        log.info("Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                summary.getProcessedControlsCount(),
                summary.getSentCount(),
                summary.getDedupedCount(),
                summary.getSkippedCount());
    }
}
