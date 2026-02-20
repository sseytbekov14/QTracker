package com.kpmg.qtracker.scheduler;

import com.kpmg.qtracker.service.DraftInitiateReminderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class DraftInitiateReminderScheduler {
    private final DraftInitiateReminderService draftInitiateReminderService;

    @Scheduled(cron = "0 0 10 * * MON-FRI", zone = "Asia/Almaty")
    public void runMorningDraftInitiateReminders() {
        runDraftInitiateReminders("morning");
    }

    @Scheduled(cron = "0 30 17 * * MON-FRI", zone = "Asia/Almaty")
    public void runEveningDraftInitiateReminders() {
        runDraftInitiateReminders("evening");
    }

    private void runDraftInitiateReminders(String window) {
        log.info("Scheduler trigger: draft initiate reminders ({})", window);
        DraftInitiateReminderService.DraftReminderRunSummary summary =
                draftInitiateReminderService.runDraftInitiateRemindersWithSummary();
        log.info("Scheduler completed ({}): processed={}, sent={}, deduped={}, skipped={}",
                window,
                summary.getProcessedControlsCount(),
                summary.getSentCount(),
                summary.getDedupedCount(),
                summary.getSkippedCount());
    }
}