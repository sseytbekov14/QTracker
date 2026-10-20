package com.kpmg.qtracker.service.notification;

import com.kpmg.qtracker.entity.EmailOutbox;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Sends the e-mails of the outbox, outside the transactions that made them (NFR-1..3). Each run:
 * <ol>
 * <li>an e-mail taken more than 5 minutes ago and never finished (the instance stopped) goes back to PENDING;</li>
 * <li>with e-mail off (notifications.email.enabled=false) every waiting e-mail becomes SKIPPED_DISABLED and is never
 * sent, also after e-mail is switched on;</li>
 * <li>every waiting e-mail is taken by one conditional UPDATE (so two instances never send one e-mail twice) and sent:
 * SENT; the person deactivated meanwhile: SKIPPED_INACTIVE; an address outside the whitelist: SKIPPED_DISABLED; a
 * failure: FAILED, tried again after 1, 5 and 15 minutes, then left FAILED with a warning. One failure does not stop
 * the others.</li>
 * </ol>
 * {@link #dispatchPending()} is public for the tests, which call it with their own Clock.
 */
@Service
@Slf4j
public class EmailOutboxDispatcher {

    static final Duration[] PAUSES = {Duration.ofMinutes(1), Duration.ofMinutes(5), Duration.ofMinutes(15)};
    /** The first try and the three retries. */
    static final int MAX_ATTEMPTS = PAUSES.length + 1;
    static final Duration STUCK = Duration.ofMinutes(5);
    private static final int BATCH = 50;
    private static final String WAITING = "(status = 'PENDING' OR (status = 'FAILED' AND next_attempt_at IS NOT NULL))";

    private final JdbcTemplate jdbc;
    private final ObjectProvider<MailTransport> transport;
    private final UserRepository userRepository;
    private final Clock clock;
    private final boolean enabled;
    private final String from;
    private final boolean whitelistEnforced;
    private final Set<String> whitelist;

    public EmailOutboxDispatcher(JdbcTemplate jdbc,
                                 ObjectProvider<MailTransport> transport,
                                 UserRepository userRepository,
                                 Clock clock,
                                 @Value("${notifications.email.enabled:false}") boolean enabled,
                                 @Value("${notifications.email.from:}") String from,
                                 @Value("${notifications.email.whitelist-enforced:false}") boolean whitelistEnforced,
                                 @Value("${notifications.email.whitelist:}") String whitelist) {
        this.jdbc = jdbc;
        this.transport = transport;
        this.userRepository = userRepository;
        this.clock = clock;
        this.enabled = enabled;
        this.from = from;
        this.whitelistEnforced = whitelistEnforced;
        this.whitelist = parseWhitelist(whitelist);
    }

    /** One run; returns how many e-mails it finished (sent, skipped or failed). */
    public synchronized int dispatchPending() {
        LocalDateTime now = LocalDateTime.now(clock);
        int released = jdbc.update("UPDATE email_outbox SET status = 'PENDING', claimed_at = NULL"
                + " WHERE status = 'SENDING' AND claimed_at < ?", Timestamp.valueOf(now.minus(STUCK)));
        if (released > 0) {
            log.warn("{} e-mail(s) taken more than {} minutes ago and never finished are waiting again", released,
                    STUCK.toMinutes());
        }
        if (!enabled) {
            return jdbc.update("UPDATE email_outbox SET status = 'SKIPPED_DISABLED', next_attempt_at = NULL,"
                    + " last_error = 'e-mail is off (NOTIFICATIONS_EMAIL_ENABLED=false)' WHERE " + WAITING);
        }
        int finished = 0;
        for (int round = 0; round < 1000; round++) {
            List<Long> ids = jdbc.queryForList("SELECT id FROM email_outbox WHERE " + WAITING
                    + " AND next_attempt_at <= ? ORDER BY id LIMIT " + BATCH, Long.class, Timestamp.valueOf(now));
            int taken = 0;
            for (Long id : ids) {
                if (claim(id, now)) {
                    taken++;
                    finished++;
                    send(id);
                }
            }
            if (ids.size() < BATCH || taken == 0) {
                break;
            }
        }
        return finished;
    }

    private boolean claim(Long id, LocalDateTime now) {
        return jdbc.update("UPDATE email_outbox SET status = 'SENDING', claimed_at = ?, attempts = attempts + 1"
                + " WHERE id = ? AND " + WAITING + " AND next_attempt_at <= ?",
                Timestamp.valueOf(now), id, Timestamp.valueOf(now)) == 1;
    }

    private void send(Long id) {
        Map<String, Object> row = jdbc.queryForMap("SELECT recipient_user_id, recipient_email, subject, body_html,"
                + " importance, attempts, control_id, template_key FROM email_outbox WHERE id = ?", id);
        String to = (String) row.get("recipient_email");
        Number userId = (Number) row.get("recipient_user_id");
        User user = userId == null ? null : userRepository.findById(userId.longValue()).orElse(null);
        if (user == null || !Boolean.TRUE.equals(user.getEnabled())) {
            finish(id, EmailOutbox.SKIPPED_INACTIVE, "the person is inactive or deleted", null);
            return;
        }
        if (!allowed(to)) {
            log.info("E-mail {} to {} skipped: not in the whitelist", id, to);
            finish(id, EmailOutbox.SKIPPED_DISABLED, "not in the whitelist", null);
            return;
        }
        MailTransport mail = transport.getIfAvailable();
        try {
            if (mail == null) {
                throw new IllegalStateException("no e-mail provider for notifications.email.provider");
            }
            mail.send(new MailTransport.OutgoingMail(from, to, (String) row.get("subject"), (String) row.get("body_html"),
                    Template.Importance.HIGH.name().equals(row.get("importance"))));
            jdbc.update("UPDATE email_outbox SET status = 'SENT', sent_at = ?, provider = ?, last_error = NULL,"
                    + " next_attempt_at = NULL, claimed_at = NULL WHERE id = ?",
                    Timestamp.valueOf(LocalDateTime.now(clock)), mail.name(), id);
        } catch (Exception failure) {
            int attempts = ((Number) row.get("attempts")).intValue();
            String error = failure.getClass().getSimpleName() + ": " + failure.getMessage();
            if (attempts < MAX_ATTEMPTS) {
                finish(id, EmailOutbox.FAILED, error, LocalDateTime.now(clock).plus(PAUSES[attempts - 1]));
                log.info("E-mail {} ({} of control {}) to {} failed, attempt {} of {}: {}", id, row.get("template_key"),
                        row.get("control_id"), to, attempts, MAX_ATTEMPTS, error);
            } else {
                finish(id, EmailOutbox.FAILED, error, null);
                log.warn("E-mail {} ({} of control {}) to {} not sent after {} attempts: {}", id,
                        row.get("template_key"), row.get("control_id"), to, attempts, error);
            }
            if (mail != null) {
                jdbc.update("UPDATE email_outbox SET provider = ? WHERE id = ?", mail.name(), id);
            }
        }
    }

    private void finish(Long id, String status, String error, LocalDateTime nextAttempt) {
        jdbc.update("UPDATE email_outbox SET status = ?, last_error = ?, next_attempt_at = ?, claimed_at = NULL"
                + " WHERE id = ?", status, error == null ? null : error.length() > 2000 ? error.substring(0, 2000) : error,
                nextAttempt == null ? null : Timestamp.valueOf(nextAttempt), id);
    }

    private boolean allowed(String to) {
        return !whitelistEnforced || whitelist.contains(to.trim().toLowerCase(Locale.ROOT));
    }

    private static Set<String> parseWhitelist(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> entries = new LinkedHashSet<>();
        for (String item : raw.split(",")) {
            String trimmed = item.trim().toLowerCase(Locale.ROOT);
            if (!trimmed.isEmpty()) {
                entries.add(trimmed);
            }
        }
        return Collections.unmodifiableSet(entries);
    }
}
