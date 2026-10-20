package com.kpmg.qtracker.service.notification;

import jakarta.mail.Session;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.mail.javamail.JavaMailSender;

import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** The providers: smtp marks high importance as Outlook reads it (NFR-5); log writes the text of the e-mail. */
class MailTransportTest {

    private MimeMessage sendBySmtp(boolean high) throws Exception {
        JavaMailSender sender = mock(JavaMailSender.class);
        when(sender.createMimeMessage()).thenAnswer(call -> new MimeMessage(Session.getInstance(new Properties())));
        SmtpMailTransport smtp = new SmtpMailTransport(
                new StaticListableBeanFactory(Map.of("sender", sender)).getBeanProvider(JavaMailSender.class));
        smtp.send(new MailTransport.OutgoingMail("from@example.test", "to@example.test", "Control Deadline Today",
                "<div>Dear Facilitator,</div>", high));
        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(sent.capture());
        return sent.getValue();
    }

    @Test
    void smtp_highImportance_hasTheImportanceAndPriorityHeaders() throws Exception {
        MimeMessage message = sendBySmtp(true);

        assertThat(message.getHeader("Importance")).containsExactly("high");
        assertThat(message.getHeader("X-Priority")).containsExactly("1");
        assertThat(message.getSubject()).isEqualTo("Control Deadline Today");
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("to@example.test");
    }

    @Test
    void smtp_normalImportance_hasNoSuchHeaders() throws Exception {
        MimeMessage message = sendBySmtp(false);

        assertThat(message.getHeader("Importance")).isNull();
        assertThat(message.getHeader("X-Priority")).isNull();
    }

    @Test
    void log_writesTheTextOfTheHtml() {
        String html = "<div style=\"x\">Dear Facilitator,<br>\n<br>\nThe control A &amp; B<br>\n"
                + "<a href=\"https://q/view-control/1\" style=\"color:#00338D\">https://q/view-control/1</a></div>";

        assertThat(LogMailTransport.text(html)).isEqualTo("Dear Facilitator,\n\nThe control A & B\nhttps://q/view-control/1");
        new LogMailTransport().send(new MailTransport.OutgoingMail("f", "t", "s", html, true));
        // The button of T12 on a line of its own
        String button = "<div>completed in the system.<table role=\"presentation\"><tr><td><a href=\"https://q/1\">Go to Control"
                + "</a></td></tr></table><p>If the button does not open, copy this address into the browser:<br>\n"
                + "<a href=\"https://q/1\">https://q/1</a></p>\nKind regards,<br>\nSoQM Team</div>";
        assertThat(LogMailTransport.text(button)).isEqualTo("completed in the system.\nGo to Control\n"
                + "If the button does not open, copy this address into the browser:\nhttps://q/1\nKind regards,\nSoQM Team");
    }
}
