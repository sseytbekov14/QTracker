package com.kpmg.qtracker.service.notification;

import jakarta.mail.Part;
import jakarta.mail.Session;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.util.Map;
import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The providers: smtp marks high importance as Outlook reads it (NFR-5) and sends multipart/alternative (the plain
 * text, then the HTML); log writes the plain text of the e-mail.
 */
class MailTransportTest {

    private static final String HTML = "<!DOCTYPE html><html><body><table><tr><td>Dear Anna,<br>\nКонтроль ✅</td></tr>"
            + "</table></body></html>";
    private static final String TEXT = "Control Deadline Today\n\nDear Anna,\nКонтроль ✅\nGo to Control: https://q/1";

    private final JavaMailSender sender = mock(JavaMailSender.class);

    /** The e-mail as the SMTP server gets it, read back from its bytes. */
    private MimeMessage sendBySmtp(String html, String text, boolean high) throws Exception {
        Session session = Session.getInstance(new Properties());
        when(sender.createMimeMessage()).thenAnswer(call -> new MimeMessage(session));
        SmtpMailTransport smtp = new SmtpMailTransport(
                new StaticListableBeanFactory(Map.of("sender", sender)).getBeanProvider(JavaMailSender.class));
        smtp.send(new MailTransport.OutgoingMail("from@example.test", "to@example.test", "Control Deadline Today",
                html, text, high));
        ArgumentCaptor<MimeMessage> sent = ArgumentCaptor.forClass(MimeMessage.class);
        verify(sender).send(sent.capture());
        sent.getValue().saveChanges();
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        sent.getValue().writeTo(raw);
        return new MimeMessage(session, new ByteArrayInputStream(raw.toByteArray()));
    }

    @Test
    void smtp_highImportance_hasTheImportanceAndPriorityHeaders() throws Exception {
        MimeMessage message = sendBySmtp(HTML, TEXT, true);

        assertThat(message.getHeader("Importance")).containsExactly("high");
        assertThat(message.getHeader("X-Priority")).containsExactly("1");
        assertThat(message.getSubject()).isEqualTo("Control Deadline Today");
        assertThat(message.getAllRecipients()[0].toString()).isEqualTo("to@example.test");
    }

    @Test
    void smtp_normalImportance_hasNoSuchHeaders() throws Exception {
        MimeMessage message = sendBySmtp(HTML, TEXT, false);

        assertThat(message.getHeader("Importance")).isNull();
        assertThat(message.getHeader("X-Priority")).isNull();
    }

    @Test
    void smtp_everyEmail_isAutoSubmitted_withNoReplyTo() throws Exception {
        for (boolean high : new boolean[]{false, true}) {
            MimeMessage message = sendBySmtp(HTML, TEXT, high);

            assertThat(message.getHeader("Auto-Submitted")).containsExactly("auto-generated");
            assertThat(message.getHeader("Reply-To")).isNull();
            clearInvocations(sender);
        }
    }

    @Test
    void smtp_multipartAlternative_thePlainTextFirst_theHtmlLast_bothUtf8() throws Exception {
        MimeMessage message = sendBySmtp(HTML, TEXT, false);

        assertThat(message.getContentType()).startsWith("multipart/alternative");
        MimeMultipart parts = (MimeMultipart) message.getContent();
        assertThat(parts.getCount()).isEqualTo(2);
        assertThat(parts.getBodyPart(0).getContentType()).startsWith("text/plain").containsIgnoringCase("charset=UTF-8");
        // On the wire the lines end in CRLF, as MIME wants for text
        assertThat(lf(parts.getBodyPart(0).getContent())).isEqualTo(TEXT);
        assertThat(parts.getBodyPart(1).getContentType()).startsWith("text/html").containsIgnoringCase("charset=UTF-8");
        assertThat(lf(parts.getBodyPart(1).getContent())).isEqualTo(HTML);
    }

    /** The HTML of the layout shows the logo by cid: it goes with the e-mail as an inline image. */
    @Test
    void smtp_theLogo_isAnInlineImage_nextToTheAlternative() throws Exception {
        String html = "<html><body><img src=\"cid:qtracker-logo\" alt=\"KPMG QTracker\"><p>Dear Anna,</p></body></html>";

        MimeMessage message = sendBySmtp(html, TEXT, false);

        assertThat(message.getContentType()).startsWith("multipart/related");
        MimeMultipart related = (MimeMultipart) message.getContent();
        assertThat(related.getCount()).isEqualTo(2);
        MimeMultipart alternative = (MimeMultipart) related.getBodyPart(0).getContent();
        assertThat(alternative.getContentType()).startsWith("multipart/alternative");
        assertThat(lf(alternative.getBodyPart(0).getContent())).isEqualTo(TEXT);
        assertThat(lf(alternative.getBodyPart(1).getContent())).isEqualTo(html);
        MimeBodyPart logo = (MimeBodyPart) related.getBodyPart(1);
        assertThat(logo.getContentType()).startsWith("image/png");
        assertThat(logo.getContentID()).isEqualTo("<qtracker-logo>");
        assertThat(logo.getDisposition()).isEqualTo(Part.INLINE);
        try (var in = new ClassPathResource(EmailLayout.LOGO_RESOURCE).getInputStream()) {
            assertThat(logo.getInputStream().readAllBytes()).isEqualTo(in.readAllBytes());
        }
        assertThat(message.getHeader("Auto-Submitted")).containsExactly("auto-generated");
    }

    @Test
    void smtp_anHtmlWithoutTheLogo_staysMultipartAlternative() throws Exception {
        assertThat(sendBySmtp(HTML, TEXT, false).getContentType()).startsWith("multipart/alternative");
    }

    @Test
    void smtp_anEmailQueuedWithoutText_getsTheTextOfItsHtml() throws Exception {
        MimeMessage message = sendBySmtp(HTML, null, false);

        MimeMultipart parts = (MimeMultipart) message.getContent();
        assertThat(lf(parts.getBodyPart(0).getContent())).isEqualTo("Dear Anna,\nКонтроль ✅");
    }

    private static String lf(Object content) {
        return String.valueOf(content).replace("\r\n", "\n");
    }

    @Test
    void log_writesThePlainText() {
        MailTransport.OutgoingMail mail = new MailTransport.OutgoingMail("f", "t", "s", HTML, TEXT, true);

        assertThat(mail.plainText()).isEqualTo(TEXT);
        new LogMailTransport().send(mail);
    }

    @Test
    void theTextOfAnOlderHtml_forAnEmailQueuedBeforeV24() {
        // The HTML of before 2026-10-30: the link "Go to Control: <address>", the button on a line of its own
        String html = "<div style=\"x\">Dear Anna,<br>\n<br>\nThe control A &amp; B<br>\n"
                + "<table role=\"presentation\" style=\"margin:12px 0\"><tr><td style=\"background:#00338D\">"
                + "<a href=\"https://q/view-control/1\" style=\"display:inline-block\">Go to Control</a></td></tr></table>\n"
                + "<br>\nKind regards,<br>\nSoQM Team</div>";

        assertThat(LogMailTransport.text(html)).isEqualTo("Dear Anna,\n\nThe control A & B\n"
                + "Go to Control: https://q/view-control/1\n\nKind regards,\nSoQM Team");
        assertThat(new MailTransport.OutgoingMail("f", "t", "s", html, "  ", false).plainText())
                .isEqualTo(LogMailTransport.text(html));
        String inline = "<div>Open <a href=\"https://q/1\" style=\"color:#00338D;font-weight:bold\">Go to Control</a> now.</div>";
        assertThat(LogMailTransport.text(inline)).isEqualTo("Open Go to Control: https://q/1 now.");
    }
}
