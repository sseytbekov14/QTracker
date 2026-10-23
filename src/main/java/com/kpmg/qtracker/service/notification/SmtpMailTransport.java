package com.kpmg.qtracker.service.notification;

import jakarta.mail.internet.MimeMessage;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;

/**
 * notifications.email.provider=smtp (the default): the SMTP relay of spring.mail.*. High importance (T4, T5) is sent as
 * the headers Importance: high and X-Priority: 1, as Outlook reads them.
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
        helper.setText(mail.html(), true);
        if (mail.highImportance()) {
            message.setHeader("Importance", "high");
            message.setHeader("X-Priority", "1");
        }
        sender.send(message);
    }
}
