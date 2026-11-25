package com.kpmg.qtracker.integration;

import com.kpmg.qtracker.entity.Control;
import com.kpmg.qtracker.entity.EmailOutbox;
import com.kpmg.qtracker.entity.User;
import com.kpmg.qtracker.enums.AccessLevel;
import com.kpmg.qtracker.service.UserService;
import com.kpmg.qtracker.service.notification.EmailOutboxDispatcher;
import com.kpmg.qtracker.service.notification.MailTransport;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Found by the deep check of 2026-11-25 in the e-mails: the Process Owner gets the overdue e-mail once a day however the
 * step changes; T2 does not follow T1 when the control was initiated on the weekend of its occurrence; an e-mail change
 * during the day makes no second reminder; when the addressee is out at sending, the copy's people still get it; a
 * person made Read Only after the e-mail was made gets none.
 */
class DeepCheckNotificationsIT extends NotificationScenarioSupport {

    static final LocalDate OCCURRENCE = LocalDate.of(2026, 11, 5);
    static final LocalDate DEADLINE = LocalDate.of(2026, 11, 19);
    static final LocalDate OVERDUE = LocalDate.of(2026, 11, 20);

    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    UserService userService;

    record Cast(User fac, User op, User soqm, User po) {
    }

    Cast cast(String tag) {
        return new Cast(myEdit(tag + "-fac"), myEdit(tag + "-op"), soqm(tag + "-soqm"), myEdit(tag + "-po"));
    }

    Control control(String id, String status, Cast cast) throws Exception {
        return dated(control(id, status, people().facilitator(cast.fac).operator(cast.op).soqm(cast.soqm).owner(cast.po)),
                OCCURRENCE, DEADLINE);
    }

    /** "T5 to (cc)" of the control's overdue e-mails, in the order made. */
    List<String> overdue(Control control) {
        return mails(control).stream().filter(m -> m.getTemplateKey().equals("T5"))
                .map(m -> m.getRecipientEmail() + (m.getCcEmails() == null ? "" : " cc " + m.getCcEmails())).toList();
    }

    static String lower(User user) {
        return user.getMail().toLowerCase(Locale.ROOT);
    }

    @Test
    void theProcessOwner_getsTheOverdueEmailOnceADay_whateverTheStepDoes() throws Exception {
        Cast cast = cast("dn1");
        Control control = control("DN1", "IN_PROGRESS", cast);

        runAt(OVERDUE.atTime(9, 35));
        status(control, "REVIEW");
        runAt(OVERDUE.atTime(11, 5));
        status(control, "PROCESS_OWNER_REVIEW");
        runAt(OVERDUE.atTime(14, 5));

        assertThat(overdue(control)).containsExactly(
                cast.fac.getMail() + " cc " + lower(cast.po),
                cast.op.getMail());
        // the next day: their own, once
        runAt(OVERDUE.plusDays(3).atTime(9, 35));
        assertThat(overdue(control)).hasSize(3).last().isEqualTo(cast.po.getMail());
    }

    @Test
    void anEmailChangeDuringTheDay_makesNoSecondReminder() throws Exception {
        Cast cast = cast("dn2");
        Control control = control("DN2", "IN_PROGRESS", cast);
        runAt(OVERDUE.atTime(9, 35));
        assertThat(overdue(control)).hasSize(1);

        userService.changeEmail(cast.fac.getId(), "dn2-new-" + cast.fac.getId() + "@release.test");
        runAt(OVERDUE.atTime(11, 5));

        assertThat(overdue(control)).hasSize(1);
        assertThat(mails(control).get(0).getDedupeKey()).contains("dn2-new-");
    }

    @Test
    void initiatedOnTheWeekendOfItsOccurrence_noT2OnTheMondayAfterT1() throws Exception {
        Cast cast = cast("dn3");
        LocalDate saturday = LocalDate.of(2026, 11, 28);
        Control control = dated(control("DN3", "IN_PROGRESS", people().facilitator(cast.fac).operator(cast.op)
                .soqm(cast.soqm).owner(cast.po)), saturday, LocalDate.of(2026, 12, 11));
        // T1 on the Saturday, as the initiation made it
        jdbc.update("INSERT INTO email_outbox (control_id, template_key, recipient_user_id, recipient_email, subject, body_html,"
                        + " importance, dedupe_key, status, attempts, created_at) VALUES (?, 'T1', ?, ?, 'Control created', '<p>x</p>',"
                        + " 'NORMAL', ?, 'SENT', 1, ?)", control.getId(), cast.fac.getId(), cast.fac.getMail(),
                control.getId() + ":T1:test", java.sql.Timestamp.valueOf(saturday.atTime(10, 0)));

        runAt(LocalDate.of(2026, 11, 30).atTime(9, 35));

        assertThat(mails(control)).extracting(EmailOutbox::getTemplateKey).doesNotContain("T2");
    }

    /** A transport that records what it sends. */
    static final class Recording implements MailTransport {
        final List<OutgoingMail> sent = Collections.synchronizedList(new ArrayList<>());

        @Override
        public String name() {
            return "test";
        }

        @Override
        public void send(OutgoingMail mail) {
            sent.add(mail);
        }
    }

    EmailOutboxDispatcher dispatcher(Recording transport) {
        StaticListableBeanFactory beans = new StaticListableBeanFactory(Map.of("transport", transport));
        return new EmailOutboxDispatcher(jdbc, beans.getBeanProvider(MailTransport.class), userRepository,
                new MutableClock(java.time.LocalDateTime.now(java.time.ZoneId.of("Asia/Almaty")).plusMinutes(1)),
                true, "qtracker@example.test", false, "");
    }

    @Test
    void theAddresseeOut_theCopyStillGetsIt_aReadOnlyPersonGetsNone() throws Exception {
        Cast cast = cast("dn4");
        Control control = control("DN4", "IN_PROGRESS", cast);
        Cast other = cast("dn5");
        Control second = control("DN5", "IN_PROGRESS", other);
        runAt(OVERDUE.atTime(9, 35));
        disable(cast.fac);
        // made Read Only after the e-mail was made
        User readOnly = userRepository.findById(other.fac.getId()).orElseThrow();
        readOnly.setAccessLevel(AccessLevel.READ_ONLY);
        userRepository.save(readOnly);
        User owner = userRepository.findById(other.po.getId()).orElseThrow();
        owner.setEnabled(false);
        userRepository.save(owner);

        Recording transport = new Recording();
        dispatcher(transport).dispatchPending();

        assertThat(transport.sent).filteredOn(m -> m.to().equalsIgnoreCase(cast.po.getMail())).singleElement()
                .satisfies(m -> assertThat(m.subject()).isNotBlank());
        assertThat(mails(control)).filteredOn(m -> m.getTemplateKey().equals("T5")).singleElement().satisfies(m -> {
            assertThat(m.getStatus()).isEqualTo(EmailOutbox.SENT);
            assertThat(m.getLastError()).startsWith("sent to the copy only: " + cast.fac.getMail());
        });
        // Read Only addressee, copy deactivated: nobody
        assertThat(transport.sent).noneMatch(m -> m.to().equalsIgnoreCase(other.fac.getMail()) || m.to().equalsIgnoreCase(other.po.getMail()));
        assertThat(mails(second)).filteredOn(m -> m.getTemplateKey().equals("T5")).singleElement()
                .satisfies(m -> assertThat(m.getLastError()).contains("Read Only or KDN"));
    }
}
