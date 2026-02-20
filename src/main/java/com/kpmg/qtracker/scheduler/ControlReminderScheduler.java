package com.kpmg.qtracker.scheduler;

import com.kpmg.qtracker.service.ReminderNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDate;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "reminders", name = "enabled", havingValue = "true", matchIfMissing = false)
public class ControlReminderScheduler {

    private final ReminderNotificationService reminderNotificationService;
    private final Clock clock;

    @Scheduled(cron = "0 30 9 * * *", zone = "Asia/Almaty")
    public void runDaily() {
        LocalDate today = LocalDate.now(clock);
        log.info("Scheduler trigger: control reminders for {}", today);
        ReminderNotificationService.ReminderRunSummary summary = reminderNotificationService.runDailyRemindersWithSummary(today);
        log.info("Scheduler completed: date={}, processed={}, sent={}, deduped={}",
                summary.getRunDate(),
                summary.getProcessedControlsCount(),
                summary.getSentCount(),
                summary.getDedupedCount());
    }
}
