package com.kpmg.qtracker.scheduler;

import com.kpmg.qtracker.service.ControlAutoCreationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.ZoneId;
import java.time.ZonedDateTime;

@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "controls.auto-create", name = "enabled", havingValue = "true", matchIfMissing = false)
public class ControlAutoCreationScheduler {

    private final ControlAutoCreationService autoCreationService;
    private final Clock clock;

    @Scheduled(cron = "0 04 15 * * *", zone = "Asia/Almaty")
    public void runDaily() {
        ZoneId zone = ZoneId.of("Asia/Almaty");
        ZonedDateTime now = ZonedDateTime.now(clock).withZoneSameInstant(zone);
        ZonedDateTime dayStart = now.toLocalDate().atStartOfDay(zone);
        ZonedDateTime nextDayStart = dayStart.plusDays(1);
        log.info("Running daily control auto-creation at {} (zone={}, dayStart={}, nextDayStart={})",
                now, zone, dayStart, nextDayStart);
        autoCreationService.runDailyAutoCreation(dayStart.toLocalDateTime(), nextDayStart.toLocalDateTime());
    }
}
