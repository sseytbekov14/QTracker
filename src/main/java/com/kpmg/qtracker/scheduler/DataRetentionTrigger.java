package com.kpmg.qtracker.scheduler;

import com.kpmg.qtracker.service.DataRetention;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Deletes the data past its retention (DataRetention: seven years from creation) on the 1st of every month at 03:00
 * Asia/Almaty; RETENTION_PURGE_ENABLED=false turns it off (java -jar ... --purge-expired still works by hand).
 */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "retention.purge", name = "enabled", havingValue = "true", matchIfMissing = true)
public class DataRetentionTrigger {

    private final DataRetention dataRetention;

    @Scheduled(cron = "0 0 3 1 * *", zone = "Asia/Almaty")
    public void run() {
        try {
            DataRetention.Report report = dataRetention.run(true, "scheduler");
            if (report.refused()) {
                log.error("Data retention refused: {}", report.refusal());
            } else if (!report.controls().isEmpty() || report.rows().values().stream().anyMatch(count -> count > 0)) {
                log.info("{}", report.render().strip());
            }
        } catch (RuntimeException e) {
            log.error("Data retention run failed", e);
        }
    }
}
