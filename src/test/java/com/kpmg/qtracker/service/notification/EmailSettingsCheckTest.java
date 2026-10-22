package com.kpmg.qtracker.service.notification;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import static org.assertj.core.api.Assertions.assertThat;

/** notifications.email.* at start: a warning (not a refusal) when the settings keep the e-mails from everybody. */
class EmailSettingsCheckTest {

    private static final String FROM = "qtracker@example.test";

    @Test
    void anEnforcedEmptyWhitelist_isAWarning_anyOtherIsNot() {
        assertThat(EmailSettingsCheck.problems(false, "", true, null)).singleElement().asString()
                .contains("NOTIFICATIONS_EMAIL_WHITELIST is empty", "no e-mail is sent to anybody");
        assertThat(EmailSettingsCheck.problems(true, FROM, true, " , ")).hasSize(1);
        assertThat(EmailSettingsCheck.problems(true, FROM, true, "someone@example.test")).isEmpty();
        assertThat(EmailSettingsCheck.problems(true, FROM, false, "")).isEmpty();
    }

    @Test
    void emailOnWithoutFrom_isAWarning_emailOffIsNot() {
        assertThat(EmailSettingsCheck.problems(true, "", false, "")).singleElement().asString()
                .contains("NOTIFICATIONS_EMAIL_FROM is empty");
        assertThat(EmailSettingsCheck.problems(true, "  ", false, "")).hasSize(1);
        assertThat(EmailSettingsCheck.problems(true, null, false, "")).hasSize(1);
        assertThat(EmailSettingsCheck.problems(false, "", false, "")).isEmpty();
        assertThat(EmailSettingsCheck.problems(true, FROM, false, "")).isEmpty();
    }

    @Test
    void theWarnings_areLoggedAtStart() {
        Logger logger = (Logger) LoggerFactory.getLogger(EmailSettingsCheck.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new EmailSettingsCheck(true, "", true, "").warnIfUnusable();
            new EmailSettingsCheck(true, FROM, true, "someone@example.test").warnIfUnusable();
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).hasSize(2).allSatisfy(event -> assertThat(event.getLevel()).isEqualTo(Level.WARN));
        assertThat(appender.list.get(0).getFormattedMessage()).contains("NOTIFICATIONS_EMAIL_FROM is empty");
        assertThat(appender.list.get(1).getFormattedMessage()).contains("NOTIFICATIONS_EMAIL_WHITELIST_ENFORCED=true");
    }
}
