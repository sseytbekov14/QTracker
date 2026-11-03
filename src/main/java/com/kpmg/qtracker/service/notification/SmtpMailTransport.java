package com.kpmg.qtracker.service.notification;

import jakarta.activation.DataHandler;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import jakarta.mail.util.ByteArrayDataSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;

/**
 * notifications.email.provider=smtp (the default): the SMTP relay of spring.mail.*. High importance (T4, T5) is sent as
 * the headers Importance: high and X-Priority: 1, as Outlook reads them. Every e-mail is automatic, so each says
 * Auto-Submitted: auto-generated (RFC 3834: no out-of-office replies to it). The e-mail is multipart/alternative: the
 * plain-text part first, the HTML last (the one a client shows when it can). An HTML with the logo (cid:qtracker-logo)
 * goes in multipart/related with the image; an e-mail queued before the logo is sent as it was.
 */
@Component
@ConditionalOnProperty(prefix = "notifications.email", name = "provider", havingValue = "smtp", matchIfMissing = true)
public class SmtpMailTransport implements MailTransport {

    private final ObjectProvider<JavaMailSender> mailSender;

    public SmtpMailTransport(ObjectProvider<JavaMailSender> mailSender) {
        this.mailSender = mailSender;
    }

    @Override
    public String name() {
        return "smtp";
    }

    @Override
    public void send(OutgoingMail mail) throws Exception {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null) {
            throw new IllegalStateException("no SMTP server (spring.mail.host is not set)");
        }
        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
        if (mail.from() != null && !mail.from().isBlank()) {
            helper.setFrom(mail.from());
        }
        helper.setTo(mail.to());
        helper.setSubject(mail.subject());
        MimeBodyPart text = new MimeBodyPart();
        text.setText(mail.plainText(), StandardCharsets.UTF_8.name());
        MimeBodyPart html = new MimeBodyPart();
        html.setText(mail.html(), StandardCharsets.UTF_8.name(), "html");
        MimeMultipart alternative = new MimeMultipart("alternative");
        alternative.addBodyPart(text);
        alternative.addBodyPart(html);
        if (mail.html() != null && mail.html().contains("cid:" + EmailLayout.LOGO_CID)) {
            // The logo goes with the HTML: multipart/related, the alternative first, the image after it
            MimeBodyPart body = new MimeBodyPart();
            body.setContent(alternative);
            MimeMultipart related = new MimeMultipart("related");
            related.addBodyPart(body);
            related.addBodyPart(logo());
            message.setContent(related);
        } else {
            message.setContent(alternative);
        }
        message.setHeader("Auto-Submitted", "auto-generated");
        if (mail.highImportance()) {
            message.setHeader("Importance", "high");
            message.setHeader("X-Priority", "1");
        }
        sender.send(message);
    }

    /** The logo of the header as an inline image, Content-ID qtracker-logo. */
    static MimeBodyPart logo() throws Exception {
        MimeBodyPart image = new MimeBodyPart();
        image.setDataHandler(new DataHandler(new ByteArrayDataSource(LOGO.get(), "image/png")));
        image.setContentID("<" + EmailLayout.LOGO_CID + ">");
        image.setDisposition(Part.INLINE);
        image.setFileName("qtracker-logo.png");
        return image;
    }

    /** The bytes of the logo, read once. */
    private static final class LOGO {

        private static final byte[] BYTES = read();

        static byte[] get() {
            return BYTES;
        }

        private static byte[] read() {
            try (InputStream in = new ClassPathResource(EmailLayout.LOGO_RESOURCE).getInputStream()) {
                return in.readAllBytes();
            } catch (IOException e) {
                throw new UncheckedIOException("no " + EmailLayout.LOGO_RESOURCE + " in the application", e);
            }
        }
    }
}
