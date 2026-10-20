package com.kpmg.qtracker.service.notification;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.EmailOutbox;
import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.repository.NotificationRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Makes a notification: for each person one row of the outbox (the e-mail, sent later by EmailOutboxDispatcher) and,
 * when that row is new, the in-app notification of every user of the address, with the same subject and text. Runs in
 * the caller's transaction (the move, the initiation, the scheduler's control): rolled back with it, nothing stays.
 * <p>The dedupe key makes each e-mail once: a key already in the outbox is skipped quietly, with no in-app copy either
 * (another run or another instance made it). A text that cannot be made (a placeholder without a value) gives one
 * FAILED_RENDER row per person, an error in the log and nothing in-app.
 */
@Service
@Slf4j
public class NotificationProducer {

    private static final String INSERT = "INSERT INTO email_outbox (control_id, template_key, recipient_user_id,"
            + " recipient_email, subject, body_html, importance, dedupe_key, status, attempts, last_error, created_at,"
            + " next_attempt_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, 0, ?, ?, ?) ON CONFLICT DO NOTHING";

    private final JdbcTemplate jdbc;
    private final NotificationRepository notificationRepository;
    private final TemplateRenderer renderer;
    private final Clock clock;

    public NotificationProducer(JdbcTemplate jdbc, NotificationRepository notificationRepository,
                                TemplateRenderer renderer, Clock clock) {
        this.jdbc = jdbc;
        this.notificationRepository = notificationRepository;
        this.renderer = renderer;
        this.clock = clock;
    }

    /**
     * @param dedupeKey the outbox key of each person's e-mail, from their address (lower case)
     * @param inAppNote added under the text of the in-app notification only (a return's comment); null for none
     * @return how many e-mails were queued (new rows, FAILED_RENDER included)
     */
    @Transactional(propagation = Propagation.REQUIRED)
    public int produce(Control control, Template template, Map<String, String> values,
                       List<NotificationRecipients.Person> people, Function<String, String> dedupeKey,
                       String inAppNote) {
        if (people.isEmpty()) {
            return 0;
        }
        TemplateRenderer.Result result = renderer.render(template, values);
        LocalDateTime now = LocalDateTime.now(clock);
        int queued = 0;
        for (NotificationRecipients.Person person : people) {
            User first = person.users().get(0);
            String key = dedupeKey.apply(person.email());
            if (!result.ok()) {
                if (insert(control, template, first, person.email(), result.rawSubject(), "", key,
                        EmailOutbox.FAILED_RENDER, result.error(), now, null)) {
                    queued++;
                    log.error("Notification {} of control {} not made for {}: {}", template, control.getId(),
                            person.email(), result.error());
                }
                continue;
            }
            TemplateRenderer.Rendered rendered = result.rendered();
            if (!insert(control, template, first, person.email(), rendered.subject(), rendered.html(), key,
                    EmailOutbox.PENDING, null, now, now)) {
                continue;
            }
            queued++;
            String message = inAppNote == null || inAppNote.isBlank()
                    ? rendered.text() : rendered.text() + "\n\n" + inAppNote.trim();
            for (User user : person.users()) {
                Notification notification = new Notification();
                notification.setUserId(user.getId());
                notification.setControlId(control.getId());
                notification.setType(template.inAppType());
                notification.setTitle(rendered.subject());
                notification.setMessage(message);
                notification.setLink(values.get(TemplateRenderer.LINK));
                notification.setIsRead(false);
                notification.setCreatedAt(now);
                notificationRepository.save(notification);
            }
        }
        return queued;
    }

    /** True when the row is new; false when the key is already in the outbox. */
    private boolean insert(Control control, Template template, User user, String email, String subject, String html,
                           String key, String status, String error, LocalDateTime now, LocalDateTime nextAttempt) {
        return jdbc.update(INSERT, control.getId(), template.name(), user.getId(), email,
                subject == null ? "" : subject, html, template.importance().name(), key, status, error,
                Timestamp.valueOf(now), nextAttempt == null ? null : Timestamp.valueOf(nextAttempt)) == 1;
    }
}
