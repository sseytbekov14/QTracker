package com.kpmg.qtracker.scheduler;

import com.kpmg.qtracker.service.QuarterlyOverdueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class QuarterlyOverdueScheduler {

    private final QuarterlyOverdueService quarterlyOverdueService;

    @Scheduled(cron = "0 53 15 * * *", zone = "Asia/Almaty")
    public void runQuarterlyOverdues() {
        log.info("Scheduler trigger: quarterly overdue reminders");
        QuarterlyOverdueService.OverdueRunSummary summary = quarterlyOverdueService.runDailyOverdues();
        log.info("Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                summary.getProcessedControlsCount(),
                summary.getSentCount(),
                summary.getDedupedCount(),
                summary.getSkippedCount());
    }
}
