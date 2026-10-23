package com.kpmg.qtracker.service.notification;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Warnings at start about notifications.email.* that would keep the e-mails from anybody, in every profile; the
 * application starts all the same:
 * <ul>
 * <li>e-mail on (NOTIFICATIONS_EMAIL_ENABLED=true) without NOTIFICATIONS_EMAIL_FROM: the e-mails go without a From
 * address, which the SMTP relay may refuse;</li>
 * <li>the whitelist enforced (NOTIFICATIONS_EMAIL_WHITELIST_ENFORCED=true) but empty: no e-mail goes to anybody, each
 * is SKIPPED_DISABLED "not in the whitelist" (2026-10-22: kept so, not "everybody").</li>
 * </ul>
 */
@Component
public class EmailSettingsCheck {

    private static final Logger log = LoggerFactory.getLogger(EmailSettingsCheck.class);

    private final boolean enabled;
    private final String from;
    private final boolean whitelistEnforced;
    private final String whitelist;

    public EmailSettingsCheck(@Value("${notifications.email.enabled:false}") boolean enabled,
                              @Value("${notifications.email.from:}") String from,
                              @Value("${notifications.email.whitelist-enforced:false}") boolean whitelistEnforced,
                              @Value("${notifications.email.whitelist:}") String whitelist) {
        this.enabled = enabled;
        this.from = from;
        this.whitelistEnforced = whitelistEnforced;
        this.whitelist = whitelist;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void warnIfUnusable() {
        problems(enabled, from, whitelistEnforced, whitelist).forEach(log::warn);
    }

    /** The warnings for these settings, none when they are usable. */
    static List<String> problems(boolean enabled, String from, boolean whitelistEnforced, String whitelist) {
        List<String> problems = new ArrayList<>();
        if (enabled && (from == null || from.isBlank())) {
            problems.add("NOTIFICATIONS_EMAIL_ENABLED=true and NOTIFICATIONS_EMAIL_FROM is empty: the e-mails go"
                    + " without a From address and the SMTP relay may refuse them; set the service mailbox");
        }
        if (whitelistEnforced && EmailOutboxDispatcher.parseWhitelist(whitelist).isEmpty()) {
            problems.add("NOTIFICATIONS_EMAIL_WHITELIST_ENFORCED=true and NOTIFICATIONS_EMAIL_WHITELIST is empty:"
                    + " no e-mail is sent to anybody (each is SKIPPED_DISABLED, not in the whitelist); list the"
                    + " addresses, or set NOTIFICATIONS_EMAIL_WHITELIST_ENFORCED=false to send to everybody");
        }
        return problems;
    }
}
