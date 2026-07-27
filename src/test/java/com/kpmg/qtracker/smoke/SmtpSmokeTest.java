package com.kpmg.qtracker.smoke;

import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.springframework.mail.javamail.MimeMessageHelper;

import java.nio.charset.StandardCharsets;
import java.util.Properties;

/**
 * Live SMTP smoke-test. Runs only when the system property 'smtp.smoke' is set.
 * Usage:
 *   mvn test -Dtest=SmtpSmokeTest -Dsmtp.smoke=true -DfailIfNoTests=false
 */
class SmtpSmokeTest {

    @Test
    void sendTestEmail() throws Exception {
        if (System.getProperty("smtp.smoke") == null) {
            System.out.println("[SmtpSmokeTest] Skipped. Run with -Dsmtp.smoke=true to execute.");
            return;
        }

        JavaMailSenderImpl sender = new JavaMailSenderImpl();
        sender.setHost("goemairs.go.kworld.kpmg.com");
        sender.setPort(25);

        Properties props = sender.getJavaMailProperties();
        props.put("mail.transport.protocol", "smtp");
        props.put("mail.smtp.auth", "false");
        props.put("mail.smtp.starttls.enable", "true");
        props.put("mail.smtp.starttls.required", "true");
        props.put("mail.smtp.connectiontimeout", "10000");
        props.put("mail.smtp.timeout", "10000");

        MimeMessage message = sender.createMimeMessage();
        MimeMessageHelper helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
        helper.setFrom("sseytbekov@kpmg.kz");
        helper.setTo("aruzhanserikbayeva@kpmg.kz");
        helper.setSubject("QTracker — SMTP smoke test");
        helper.setText(
                "<p>Привет!</p>" +
                "<p>Это тестовое письмо от <strong>QTracker</strong>.</p>" +
                "<p>SMTP relay подключён успешно.</p>",
                true
        );

        sender.send(message);
        System.out.println("[SmtpSmokeTest] Email sent to aruzhanserikbayeva@kpmg.kz");
    }
}
