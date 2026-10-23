package com.kpmg.qtracker.service.notification;

import jakarta.mail.MessagingException;
import jakarta.mail.internet.InternetAddress;
import org.eclipse.angus.mail.smtp.SMTPAddressFailedException;
import org.eclipse.angus.mail.smtp.SMTPSendFailedException;
import org.junit.jupiter.api.Test;
import org.springframework.mail.MailSendException;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** The reply code of the warning of a failed try: found however deep Spring and Jakarta Mail wrap it. */
class EmailOutboxDispatcherTest {

    @Test
    void theSmtpReplyCode_isFoundInTheCauses_orIsADash() throws Exception {
        SMTPSendFailedException data = new SMTPSendFailedException("DATA", 554, "554 rejected", null, null, null, null);
        assertThat(EmailOutboxDispatcher.responseCode(new MailSendException("Failed", data))).isEqualTo("554");
        assertThat(EmailOutboxDispatcher.responseCode(new MailSendException(Map.<Object, Exception>of(new Object(),
                new MessagingException("send failed", data))))).isEqualTo("554");

        SMTPAddressFailedException address = new SMTPAddressFailedException(
                new InternetAddress("nobody@example.test"), "RCPT TO", 550, "550 no such user");
        assertThat(EmailOutboxDispatcher.responseCode(new MessagingException("send failed", address))).isEqualTo("550");

        assertThat(EmailOutboxDispatcher.responseCode(new IllegalStateException("no SMTP server"))).isEqualTo("-");
        assertThat(EmailOutboxDispatcher.responseCode(new MailSendException("Mail server connection failed",
                new MessagingException("Couldn't connect to host")))).isEqualTo("-");
    }
}
