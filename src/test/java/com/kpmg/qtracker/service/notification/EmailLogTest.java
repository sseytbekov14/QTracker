package com.kpmg.qtracker.service.notification;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

/** The E-mails page's words for the stored states and the mail server's answers. */
class EmailLogTest {

    private static final LocalDateTime NEXT = LocalDateTime.of(2026, 11, 10, 14, 35);

    @Test
    void theStoredStates_readAsFourStatuses() {
        assertThat(EmailLog.Status.of("SENT", null)).isEqualTo(EmailLog.Status.SENT);
        assertThat(EmailLog.Status.of("PENDING", null)).isEqualTo(EmailLog.Status.WAITING);
        assertThat(EmailLog.Status.of("SENDING", null)).isEqualTo(EmailLog.Status.WAITING);
        assertThat(EmailLog.Status.of("FAILED", NEXT)).isEqualTo(EmailLog.Status.WAITING);
        assertThat(EmailLog.Status.of("FAILED", null)).isEqualTo(EmailLog.Status.FAILED);
        assertThat(EmailLog.Status.of("FAILED_RENDER", null)).isEqualTo(EmailLog.Status.FAILED);
        assertThat(EmailLog.Status.of("SKIPPED_DISABLED", null)).isEqualTo(EmailLog.Status.NOT_SENT);
        assertThat(EmailLog.Status.of("SKIPPED_INACTIVE", null)).isEqualTo(EmailLog.Status.NOT_SENT);
        assertThat(EmailLog.Status.parse(" waiting ")).isEqualTo(EmailLog.Status.WAITING);
        assertThat(EmailLog.Status.parse("nonsense")).isNull();
    }

    @Test
    void eachState_saysWhatHappened_inPlainWords() {
        assertThat(EmailLog.detail("SENT", null, null, "smtp", 1)).isEqualTo("Accepted by the mail server");
        assertThat(EmailLog.detail("SENT", null, null, "log", 1)).startsWith("Test mode");
        assertThat(EmailLog.detail("PENDING", null, null, null, 0)).startsWith("In the queue");
        assertThat(EmailLog.detail("FAILED", NEXT, "MailConnectException: Couldn't connect to host", "smtp", 1))
                .isEqualTo("Try 1 failed (the mail server could not be reached), next try at 10.11.2026 14:35");
        assertThat(EmailLog.detail("FAILED", null, "SendFailedException: 550 5.1.1 Invalid Addresses", "smtp", 3))
                .isEqualTo("the mail server did not accept the address; tried 3 times, no more tries");
        assertThat(EmailLog.detail("FAILED", null, "AuthenticationFailedException: 535", "smtp", 3))
                .startsWith("the mail server refused the login");
        assertThat(EmailLog.detail("FAILED", null, "IllegalStateException: something", "smtp", 3))
                .startsWith("the mail server returned an error");
        assertThat(EmailLog.detail("FAILED_RENDER", null, "x", null, 0)).isEqualTo("The text of the e-mail could not be made");
        assertThat(EmailLog.detail("SKIPPED_INACTIVE", null, "the person is inactive or deleted", null, 0))
                .isEqualTo("The person is inactive or deleted");
        assertThat(EmailLog.detail("SKIPPED_DISABLED", null, "not in the whitelist", null, 0))
                .isEqualTo("The address is not in the e-mail whitelist of the server");
        assertThat(EmailLog.detail("SKIPPED_DISABLED", null, "e-mail is off (NOTIFICATIONS_EMAIL_ENABLED=false)", null, 0))
                .isEqualTo("E-mail is turned off on the server");
    }

    @Test
    void everyTemplate_hasAnEventName() {
        for (Template template : Template.values()) {
            assertThat(EmailLog.EVENTS).as(template.name()).containsKey(template.name());
        }
    }
}
