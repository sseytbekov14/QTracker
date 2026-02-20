package com.kpmg.qtracker.scheduler;

import com.kpmg.qtracker.service.MonthlyOverdueService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class MonthlyOverdueScheduler {

    private final MonthlyOverdueService monthlyOverdueService;

    @Scheduled(cron = "0 9 13 * * *", zone = "Asia/Almaty")
    public void runMonthlyOverdues() {
        log.info("Scheduler trigger: monthly overdue reminders");
        MonthlyOverdueService.OverdueRunSummary summary = monthlyOverdueService.runDailyOverdues();
        log.info("Scheduler completed: processed={}, sent={}, deduped={}, skipped={}",
                summary.getProcessedControlsCount(),
                summary.getSentCount(),
                summary.getDedupedCount(),
                summary.getSkippedCount());
    }
}