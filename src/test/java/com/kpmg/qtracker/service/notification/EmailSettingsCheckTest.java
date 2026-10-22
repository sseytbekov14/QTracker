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

    @Test
    void anEnforcedEmptyWhitelist_isAWarning_anyOtherIsNot() {
        assertThat(EmailSettingsCheck.problems(true, null)).singleElement().asString()
                .contains("NOTIFICATIONS_EMAIL_WHITELIST is empty", "no e-mail is sent to anybody");
        assertThat(EmailSettingsCheck.problems(true, " , ")).hasSize(1);
        assertThat(EmailSettingsCheck.problems(true, "someone@example.test")).isEmpty();
        assertThat(EmailSettingsCheck.problems(false, "")).isEmpty();
    }

    @Test
    void theWarning_isLoggedAtStart() {
        Logger logger = (Logger) LoggerFactory.getLogger(EmailSettingsCheck.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new EmailSettingsCheck(true, "").warnIfUnusable();
            new EmailSettingsCheck(true, "someone@example.test").warnIfUnusable();
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("NOTIFICATIONS_EMAIL_WHITELIST_ENFORCED=true");
        });
    }
}
