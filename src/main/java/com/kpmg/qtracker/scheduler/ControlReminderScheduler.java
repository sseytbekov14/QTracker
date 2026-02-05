package com.kpmg.qtracker.scheduler;

import com.kpmg.qtracker.service.ReminderNotificationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "reminders", name = "enabled", havingValue = "true", matchIfMissing = false)
public class ControlReminderScheduler {

    private static final ZoneId ZONE_ID = ZoneId.of("Asia/Almaty");

    private final ReminderNotificationService reminderNotificationService;

    @Scheduled(cron = "0 30 9 * * *", zone = "Asia/Almaty")
    public void runDaily() {
        LocalDate today = LocalDate.now(ZONE_ID);
        log.info("Running daily control reminders for {}", today);
        reminderNotificationService.runDailyReminders(today);
    }
}
