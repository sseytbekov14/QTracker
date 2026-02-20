package com.kpmg.qtracker.scheduler;

import com.kpmg.qtracker.service.AdhocOverdueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AdhocOverdueScheduler {

    private final AdhocOverdueService adhocOverdueService;

    @Scheduled(cron = "0 30 09 * * *", zone = "Asia/Almaty")
    public void runAdhocOverdues() {
        log.info("Scheduler trigger: ad-hoc overdue reminders");
        AdhocOverdueService.OverdueRunSummary summary = adhocOverdueService.runDailyOverdues();
        log.info("Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                summary.getProcessedControlsCount(),
                summary.getSentCount(),
                summary.getDedupedCount(),
                summary.getSkippedCount());
    }
}
