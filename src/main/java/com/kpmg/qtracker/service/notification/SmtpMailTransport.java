package com.kpmg.qtracker.service.notification;

import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * notifications.email.provider=smtp (the default): the SMTP relay of spring.mail.*. High importance (T4, T5) is sent as
 * the headers Importance: high and X-Priority: 1, as Outlook reads them. Every e-mail is automatic, so each says
 * Auto-Submitted: auto-generated (RFC 3834: no out-of-office replies to it). The e-mail is multipart/alternative: the
 * plain-text part first, the HTML last (the one a client shows when it can).
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
        message.setContent(alternative);
        message.setHeader("Auto-Submitted", "auto-generated");
        if (mail.highImportance()) {
            message.setHeader("Importance", "high");
            message.setHeader("X-Priority", "1");
        }
        sender.send(message);
    }
}
