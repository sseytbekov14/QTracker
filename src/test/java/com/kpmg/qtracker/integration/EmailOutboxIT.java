package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.dto.ControlAssignmentDTO;
import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.EmailOutbox;
import com.kpmg.qtracker.entity.Notification;
import com.kpmg.qtracker.entity.NotificationTemplateText;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.enums.AccessScope;
import com.kpmg.qtracker.repository.EmailOutboxRepository;
import com.kpmg.qtracker.repository.NotificationTemplateTextRepository;
import com.kpmg.qtracker.service.ControlAssignmentService;
import com.kpmg.qtracker.service.UserService;
import com.kpmg.qtracker.service.notification.EmailOutboxDispatcher;
import com.kpmg.qtracker.service.notification.MailTransport;
import com.kpmg.qtracker.service.notification.NotificationProducer;
import com.kpmg.qtracker.service.notification.NotificationRecipients;
import com.kpmg.qtracker.service.notification.NotificationValues;
import com.kpmg.qtracker.service.notification.Template;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The e-mail outbox (NOTIFICATIONS_v5, NFR-1..3): the producer writes the e-mail and the in-app notification in the
 * caller's transaction, once per dedupe key; the dispatcher sends outside it, retries 1/5/15 minutes, skips an inactive
 * person and everything while e-mail is off, and never sends one e-mail twice from two instances. The dispatchers here
 * are made by the test with their own clock and transport.
 */
class EmailOutboxIT extends ReleaseScenarioSupport {

    private static final ZoneId ALMATY = ZoneId.of("Asia/Almaty");

    @Autowired
    NotificationProducer producer;
    @Autowired
    NotificationRecipients recipients;
    @Autowired
    NotificationValues values;
    @Autowired
    ControlAssignmentService assignments;
    @Autowired
    EmailOutboxRepository outbox;
    @Autowired
    NotificationTemplateTextRepository templates;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    PlatformTransactionManager transactionManager;
    @Autowired
    UserService userService;

    /** A transport that records what it sends and fails for the addresses it is told to. */
    static final class RecordingTransport implements MailTransport {
        final List<OutgoingMail> sent = Collections.synchronizedList(new ArrayList<>());
        final List<String> failFor = Collections.synchronizedList(new ArrayList<>());
        volatile long delayMs;

        @Override
        public String name() {
            return "test";
        }

        @Override
        public void send(OutgoingMail mail) throws Exception {
            if (delayMs > 0) {
                Thread.sleep(delayMs);
            }
            if (failFor.contains(mail.to())) {
                throw new IllegalStateException("mail server down");
            }
            sent.add(mail);
        }

        long count(String to) {
            synchronized (sent) {
                return sent.stream().filter(mail -> mail.to().equals(to)).count();
            }
        }
    }

    EmailOutboxDispatcher dispatcher(MutableClock clock, MailTransport transport, boolean enabled) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("transport", transport));
        return new EmailOutboxDispatcher(jdbc, beans.getBeanProvider(MailTransport.class), userRepository, clock,
                enabled, "qtracker@example.test", false, "");
    }

    static MutableClock soon() {
        return new MutableClock(LocalDateTime.now(ALMATY).plusSeconds(5).withNano(0));
    }

    /** T3 to the people given, key per address, in a transaction of its own. */
    int produce(Control control, Template template, List<String> addresses, String keyLabel) {
        return new TransactionTemplate(transactionManager).execute(status -> {
            ControlAssignmentDTO assignment = assignments.getAssignmentByControlId(control.getId());
            Map<String, String> placeholders = values.of(control, assignment);
            placeholders.put("ROLE", "Facilitator");
            return producer.produce(control, template, placeholders,
                    recipients.forControl(control.getControlId()).add("Facilitator", addresses).people(),
                    email -> control.getId() + ":" + template + ":" + keyLabel + ":" + email, null);
        });
    }

    List<EmailOutbox> rows(Control control) {
        return outbox.findByControlIdOrderByIdAsc(control.getId());
    }

    @Test
    void theTemplates_areTheTextsOfV5() {
        for (Template template : Template.values()) {
            NotificationTemplateText text = templates.findById(template.name()).orElseThrow();
            assertThat(text.getSubject()).as(template.name()).isEqualTo(template.defaultSubject());
            assertThat(text.getBody()).as(template.name()).isEqualTo(template.defaultBody());
        }
        assertThat(templates.count()).isEqualTo(12);
    }

    @Test
    void oneEmailPerAddress_inAppForEveryUserOfIt_onceForAKey() throws Exception {
        User fac = myEdit("ob-fac");
        // The same address in another case: one e-mail, both get the in-app notification
        User twin = person("ob-twin", AccessLevel.PARTICIPANT, AccessScope.OWN);
        twin.setMail(fac.getMail().toUpperCase(Locale.ROOT));
        twin = userRepository.save(twin);
        User inactive = disable(myEdit("ob-off"));
        User kdn = kdn("ob-kdn");
        User readOnly = myRead("ob-ro");
        Control control = control("OB-ONE", "IN_PROGRESS", people().facilitator(fac));

        List<String> addresses = List.of(fac.getMail(), inactive.getMail(), kdn.getMail(), readOnly.getMail(),
                "nobody-" + RUN + "@release.test", fac.getMail().toUpperCase(Locale.ROOT));
        assertThat(produce(control, Template.T3, addresses, "D3")).isEqualTo(1);

        assertThat(rows(control)).singleElement().satisfies(row -> {
            assertThat(row.getRecipientEmail()).isEqualTo(fac.getMail());
            assertThat(row.getStatus()).isEqualTo(EmailOutbox.PENDING);
            assertThat(row.getTemplateKey()).isEqualTo("T3");
            assertThat(row.getImportance()).isEqualTo("NORMAL");
            assertThat(row.getSubject()).isEqualTo("Control Reminder");
            assertThat(row.getBodyHtml()).contains("Dear Facilitator,", control.getControlId(),
                    "/view-control/" + control.getId() + "\" style=");
        });
        List<Notification> notices = notificationRepository.findByControlIdOrderByCreatedAtDesc(control.getId());
        assertThat(notices).extracting(Notification::getUserId).containsExactlyInAnyOrder(fac.getId(), twin.getId());
        assertThat(notices).allSatisfy(notice -> {
            assertThat(notice.getTitle()).isEqualTo("Control Reminder");
            assertThat(notice.getType()).isEqualTo("REMINDER");
            assertThat(notice.getMessage()).startsWith("Dear Facilitator,").contains("Kind regards,\nSoQM Team");
        });

        // The same key again (another run, another instance): nothing new, no second in-app notification
        assertThat(produce(control, Template.T3, addresses, "D3")).isZero();
        assertThat(rows(control)).hasSize(1);
        assertThat(notificationRepository.findByControlIdOrderByCreatedAtDesc(control.getId())).hasSize(2);
    }

    @Test
    void aPlaceholderWithoutValue_isFailedRender_withNoInAppNotification() throws Exception {
        User fac = myEdit("ob-render");
        Control control = control("OB-RENDER", "IN_PROGRESS", people().facilitator(fac));
        jdbc.update("UPDATE controls SET control_operation_deadline = NULL WHERE id = ?", control.getId());

        assertThat(produce(control, Template.T3, List.of(fac.getMail()), "D3")).isEqualTo(1);

        assertThat(rows(control)).singleElement().satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(EmailOutbox.FAILED_RENDER);
            assertThat(row.getLastError()).contains("{DEADLINE}");
        });
        assertThat(notificationRepository.findByControlIdOrderByCreatedAtDesc(control.getId())).isEmpty();
        // The dispatcher never sends it
        RecordingTransport transport = new RecordingTransport();
        dispatcher(soon(), transport, true).dispatchPending();
        assertThat(transport.count(fac.getMail())).isZero();
        assertThat(rows(control).get(0).getStatus()).isEqualTo(EmailOutbox.FAILED_RENDER);
    }

    @Test
    void aRolledBackTransaction_leavesNoEmailAndNoInAppNotification() throws Exception {
        User fac = myEdit("ob-rollback");
        Control control = control("OB-ROLLBACK", "IN_PROGRESS", people().facilitator(fac));

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            Map<String, String> placeholders = values.of(control, assignments.getAssignmentByControlId(control.getId()));
            placeholders.put("ROLE", "Facilitator");
            producer.produce(control, Template.T3, placeholders,
                    recipients.forControl(control.getControlId()).add("Facilitator", List.of(fac.getMail())).people(),
                    email -> control.getId() + ":T3:D3:" + email, null);
            status.setRollbackOnly();
        });

        assertThat(rows(control)).isEmpty();
        assertThat(notificationRepository.findByControlIdOrderByCreatedAtDesc(control.getId())).isEmpty();
    }

    @Test
    void theDispatcher_sendsOnce_withTheImportanceOfTheTemplate() throws Exception {
        User fac = myEdit("ob-send");
        Control control = control("OB-SEND", "IN_PROGRESS", people().facilitator(fac));
        produce(control, Template.T4, List.of(fac.getMail()), "DL");
        RecordingTransport transport = new RecordingTransport();
        MutableClock clock = soon();

        dispatcher(clock, transport, true).dispatchPending();
        dispatcher(clock.plus(Duration.ofMinutes(1)), transport, true).dispatchPending();

        assertThat(transport.count(fac.getMail())).isEqualTo(1);
        MailTransport.OutgoingMail mail = transport.sent.stream().filter(m -> m.to().equals(fac.getMail())).findFirst().orElseThrow();
        assertThat(mail.subject()).isEqualTo("Control Deadline Today");
        assertThat(mail.highImportance()).isTrue();
        assertThat(mail.from()).isEqualTo("qtracker@example.test");
        assertThat(rows(control)).singleElement().satisfies(row -> {
            assertThat(row.getStatus()).isEqualTo(EmailOutbox.SENT);
            assertThat(row.getImportance()).isEqualTo("HIGH");
            assertThat(row.getProvider()).isEqualTo("test");
            assertThat(row.getAttempts()).isEqualTo(1);
            assertThat(row.getSentAt()).isNotNull();
        });
    }

    @Test
    void aFailure_isTriedAgainAfter1_5_15Minutes_thenLeftFailed_andDoesNotStopTheOthers() throws Exception {
        User down = myEdit("ob-down");
        User fine = myEdit("ob-fine");
        Control control = control("OB-RETRY", "IN_PROGRESS", people().facilitator(down, fine));
        produce(control, Template.T3, List.of(down.getMail(), fine.getMail()), "D3");
        RecordingTransport transport = new RecordingTransport();
        transport.failFor.add(down.getMail());
        MutableClock clock = soon();
        EmailOutboxDispatcher dispatcher = dispatcher(clock, transport, true);

        dispatcher.dispatchPending();
        assertThat(transport.count(fine.getMail())).isEqualTo(1);
        EmailOutbox failed = row(control, down);
        assertThat(failed.getStatus()).isEqualTo(EmailOutbox.FAILED);
        assertThat(failed.getAttempts()).isEqualTo(1);
        assertThat(failed.getLastError()).contains("mail server down");
        assertThat(failed.getNextAttemptAt()).isEqualTo(clock.now().plusMinutes(1));

        // Not before the pause
        dispatcher.dispatchPending();
        clock.plus(Duration.ofSeconds(59));
        dispatcher.dispatchPending();
        assertThat(row(control, down).getAttempts()).isEqualTo(1);

        int[] pauses = {5, 15};
        clock.plus(Duration.ofSeconds(1));
        dispatcher.dispatchPending();
        assertThat(row(control, down).getAttempts()).isEqualTo(2);
        for (int pause : pauses) {
            assertThat(row(control, down).getNextAttemptAt()).isEqualTo(clock.now().plusMinutes(pause));
            clock.plus(Duration.ofMinutes(pause));
            dispatcher.dispatchPending();
        }
        EmailOutbox gaveUp = row(control, down);
        assertThat(gaveUp.getAttempts()).isEqualTo(4);
        assertThat(gaveUp.getStatus()).isEqualTo(EmailOutbox.FAILED);
        assertThat(gaveUp.getNextAttemptAt()).isNull();

        clock.plus(Duration.ofHours(2));
        transport.failFor.clear();
        dispatcher.dispatchPending();
        assertThat(transport.count(down.getMail())).isZero();
        assertThat(row(control, fine).getStatus()).isEqualTo(EmailOutbox.SENT);
    }

    @Test
    void emailOff_skipsEverything_andNothingIsSentAfterItIsSwitchedOn() throws Exception {
        User fac = myEdit("ob-off-mail");
        Control control = control("OB-MAILOFF", "IN_PROGRESS", people().facilitator(fac));
        produce(control, Template.T3, List.of(fac.getMail()), "D3");
        RecordingTransport transport = new RecordingTransport();
        MutableClock clock = soon();

        dispatcher(clock, transport, false).dispatchPending();
        assertThat(rows(control)).singleElement().extracting(EmailOutbox::getStatus).isEqualTo(EmailOutbox.SKIPPED_DISABLED);
        assertThat(notificationRepository.findByControlIdOrderByCreatedAtDesc(control.getId())).hasSize(1);

        dispatcher(clock.plus(Duration.ofMinutes(1)), transport, true).dispatchPending();
        assertThat(transport.count(fac.getMail())).isZero();
        assertThat(rows(control)).singleElement().extracting(EmailOutbox::getStatus).isEqualTo(EmailOutbox.SKIPPED_DISABLED);
    }

    @Test
    void aPersonDeactivatedAfterTheQueueing_isSkippedInactive() throws Exception {
        User fac = myEdit("ob-late-off");
        Control control = control("OB-LATEOFF", "IN_PROGRESS", people().facilitator(fac));
        produce(control, Template.T3, List.of(fac.getMail()), "D3");
        disable(fac);
        RecordingTransport transport = new RecordingTransport();

        dispatcher(soon(), transport, true).dispatchPending();

        assertThat(transport.count(fac.getMail())).isZero();
        assertThat(rows(control)).singleElement().extracting(EmailOutbox::getStatus).isEqualTo(EmailOutbox.SKIPPED_INACTIVE);
    }

    @Test
    void anEmailTakenAndNeverFinished_isSentAfterFiveMinutes() throws Exception {
        User fac = myEdit("ob-stuck");
        Control control = control("OB-STUCK", "IN_PROGRESS", people().facilitator(fac));
        produce(control, Template.T3, List.of(fac.getMail()), "D3");
        MutableClock clock = soon();
        // An instance took it and stopped
        jdbc.update("UPDATE email_outbox SET status = 'SENDING', attempts = 1, claimed_at = ? WHERE control_id = ?",
                java.sql.Timestamp.valueOf(clock.now()), control.getId());
        RecordingTransport transport = new RecordingTransport();

        dispatcher(clock.plus(Duration.ofMinutes(4)), transport, true).dispatchPending();
        assertThat(transport.count(fac.getMail())).isZero();
        dispatcher(clock.plus(Duration.ofMinutes(2)), transport, true).dispatchPending();
        assertThat(transport.count(fac.getMail())).isEqualTo(1);
        assertThat(rows(control)).singleElement().extracting(EmailOutbox::getStatus).isEqualTo(EmailOutbox.SENT);
    }

    @Test
    void twoInstancesAtOnce_sendEveryEmailOnce() throws Exception {
        List<User> people = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            people.add(myEdit("ob-race"));
        }
        Control control = control("OB-RACE", "IN_PROGRESS", people().facilitator(people.toArray(User[]::new)));
        produce(control, Template.T3, people.stream().map(User::getMail).toList(), "D3");
        RecordingTransport transport = new RecordingTransport();
        transport.delayMs = 15;
        MutableClock clock = soon();
        EmailOutboxDispatcher first = dispatcher(clock, transport, true);
        EmailOutboxDispatcher second = dispatcher(clock, transport, true);

        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        for (EmailOutboxDispatcher dispatcher : List.of(first, second)) {
            pool.submit(() -> {
                start.await();
                return dispatcher.dispatchPending();
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(60, TimeUnit.SECONDS)).isTrue();

        for (User person : people) {
            assertThat(transport.count(person.getMail())).as(person.getMail()).isEqualTo(1);
        }
        assertThat(rows(control)).hasSize(12).allSatisfy(row -> assertThat(row.getStatus()).isEqualTo(EmailOutbox.SENT));
    }

    @Test
    void anAddressOfTwoUsersInAnotherCase_findsOneUser_neverFails() {
        User first = myEdit("ob-dup");
        User second = person("ob-dup2", AccessLevel.PARTICIPANT, AccessScope.OWN);
        second.setMail(first.getMail().toUpperCase(Locale.ROOT));
        userRepository.save(second);

        assertThat(userRepository.findByMail(first.getMail().toUpperCase(Locale.ROOT))).get()
                .extracting(User::getId).isEqualTo(first.getId());
        disable(first);
        assertThat(userRepository.findByMail(first.getMail())).get().extracting(User::getId).isEqualTo(second.getId());
        assertThat(userRepository.findAllByMail(" " + first.getMail() + " ")).hasSize(2);

        // QTracker makes no such pair itself: a new user or a changed address taken in another case is refused
        User other = myEdit("ob-dup3");
        assertThatThrownBy(() -> userService.createUser(other.getMail().toUpperCase(Locale.ROOT), "Copy", "USER", "MY",
                "EDIT", true)).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("already exists");
        User fresh = myEdit("ob-dup4");
        assertThatThrownBy(() -> userService.updateUser(fresh.getId(), other.getMail().toUpperCase(Locale.ROOT), null,
                null, null, null, null, fresh.getId())).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("already exists");
    }

    private EmailOutbox row(Control control, User user) {
        return rows(control).stream().filter(row -> row.getRecipientEmail().equals(user.getMail())).findFirst().orElseThrow();
    }
}
