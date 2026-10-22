package com.kpmg.qtracker.service.notification;

import com.kpmg.qtracker.entity.EmailOutbox;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.UserRepository;
import jakarta.mail.MessagingException;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.eclipse.angus.mail.smtp.SMTPSenderFailedException;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.MailSendException;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Deque;
import java.util.IdentityHashMap;
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
 * failure: FAILED, tried again after 1, 5 and 15 minutes, then left FAILED; every failed try is a warning with the
 * recipient, the Control ID, the SMTP reply code and the attempt, never the text of the e-mail. One failure does not stop
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
        Map<String, Object> row = jdbc.queryForMap("SELECT o.recipient_user_id, o.recipient_email, o.subject,"
                + " o.body_html, o.importance, o.attempts, o.control_id, o.template_key, c.control_id AS control_code"
                + " FROM email_outbox o LEFT JOIN controls c ON c.id = o.control_id WHERE o.id = ?", id);
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
            // Never the subject or the text of the e-mail: the recipient, the control, the server's answer
            if (attempts < MAX_ATTEMPTS) {
                Duration pause = PAUSES[attempts - 1];
                finish(id, EmailOutbox.FAILED, error, LocalDateTime.now(clock).plus(pause));
                log.warn("E-mail {} ({}) to {} for control {} failed, response code {}, attempt {} of {},"
                                + " next try in {} min: {}", id, row.get("template_key"), to, control(row),
                        responseCode(failure), attempts, MAX_ATTEMPTS, pause.toMinutes(), error);
            } else {
                finish(id, EmailOutbox.FAILED, error, null);
                log.warn("E-mail {} ({}) to {} for control {} failed, response code {}, attempt {} of {},"
                                + " not sent, no more tries: {}", id, row.get("template_key"), to, control(row),
                        responseCode(failure), attempts, MAX_ATTEMPTS, error);
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

    /** The Control ID of the e-mail's control (its row id when the control is gone), or "-" for none. */
    private static String control(Map<String, Object> row) {
        Object code = row.get("control_code");
        Object id = row.get("control_id");
        return code != null ? code.toString() : id != null ? "id " + id : "-";
    }

    /**
     * The SMTP server's reply code (550, 452, ...) found in the failure or its causes, or "-" when there is none (no
     * connection, no server, another provider).
     */
    static String responseCode(Throwable failure) {
        Deque<Throwable> pending = new ArrayDeque<>();
        Set<Throwable> seen = Collections.newSetFromMap(new IdentityHashMap<>());
        pending.add(failure);
        while (!pending.isEmpty()) {
            Throwable next = pending.poll();
            if (next == null || !seen.add(next)) {
                continue;
            }
            if (next instanceof SMTPSendFailedException smtp) {
                return String.valueOf(smtp.getReturnCode());
            }
            if (next instanceof SMTPAddressFailedException smtp) {
                return String.valueOf(smtp.getReturnCode());
            }
            if (next instanceof SMTPSenderFailedException smtp) {
                return String.valueOf(smtp.getReturnCode());
            }
            if (next instanceof MessagingException messaging && messaging.getNextException() != null) {
                pending.add(messaging.getNextException());
            }
            if (next instanceof MailSendException send) {
                pending.addAll(send.getFailedMessages().values());
            }
            if (next.getCause() != null) {
                pending.add(next.getCause());
            }
        }
        return "-";
    }

    private boolean allowed(String to) {
        return !whitelistEnforced || whitelist.contains(to.trim().toLowerCase(Locale.ROOT));
    }

    /** The addresses of notifications.email.whitelist, lower-case; empty for none. */
    static Set<String> parseWhitelist(String raw) {
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
