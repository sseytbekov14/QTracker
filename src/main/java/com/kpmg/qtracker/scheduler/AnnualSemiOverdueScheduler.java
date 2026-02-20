package com.kpmg.qtracker.scheduler;

import com.kpmg.qtracker.service.AnnualSemiOverdueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AnnualSemiOverdueScheduler {

    private final AnnualSemiOverdueService annualSemiOverdueService;

    @Scheduled(cron = "0 30 09 * * *", zone = "Asia/Almaty")
    public void runAnnualSemiOverdues() {
        log.info("Scheduler trigger: annual/semi overdue reminders");
        AnnualSemiOverdueService.OverdueRunSummary summary = annualSemiOverdueService.runDailyOverdues();
        log.info("Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                summary.getProcessedControlsCount(),
                summary.getSentCount(),
                summary.getDedupedCount(),
                summary.getSkippedCount());
    }
}
