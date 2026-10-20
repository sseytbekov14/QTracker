package com.kpmg.qtracker.scheduler;

import com.kpmg.qtracker.service.notification.EmailOutboxDispatcher;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Runs the dispatcher of the e-mail outbox every 20 seconds (notifications.dispatcher.*; the tests call it). */
@Component
@RequiredArgsConstructor
@Slf4j
@ConditionalOnProperty(prefix = "notifications.dispatcher", name = "enabled", havingValue = "true", matchIfMissing = true)
public class EmailOutboxDispatchTrigger {

    private final EmailOutboxDispatcher dispatcher;

    @Scheduled(fixedDelayString = "${notifications.dispatcher.delay-ms:20000}",
            initialDelayString = "${notifications.dispatcher.delay-ms:20000}")
    public void dispatch() {
        try {
            int finished = dispatcher.dispatchPending();
            if (finished > 0) {
                log.debug("E-mail outbox: {} finished", finished);
            }
        } catch (RuntimeException e) {
            log.error("E-mail outbox run failed", e);
        }
    }
}
