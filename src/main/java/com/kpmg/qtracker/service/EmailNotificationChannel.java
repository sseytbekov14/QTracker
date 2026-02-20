package com.kpmg.qtracker.service;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
@Slf4j
@Profile({"local", "demo"})
@ConditionalOnProperty(prefix = "notifications.email", name = "enabled", havingValue = "true")
public class EmailNotificationChannel {

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final boolean whitelistEnforced;
    private final Set<String> whitelist;

    public EmailNotificationChannel(JavaMailSender mailSender,
                                    @Value("${spring.mail.username:}") String fromAddress,
                                    @Value("${notifications.email.whitelist:}") String whitelist,
                                    @Value("${notifications.email.whitelist-enforced:false}") boolean whitelistEnforced) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.whitelistEnforced = whitelistEnforced;
        this.whitelist = parseWhitelist(whitelist);
    }

    public void send(String to, String subject, String body) {
        if (to == null || to.isBlank()) {
            return;
        }
        if (!isAllowedRecipient(to)) {
            log.info("Skipping email to {} (not in whitelist)", to);
            return;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, StandardCharsets.UTF_8.name());
            if (fromAddress != null && !fromAddress.isBlank()) {
                helper.setFrom(fromAddress);
            }
            helper.setTo(to);
            helper.setSubject(subject != null ? subject : "");
            helper.setText(toHtml(body), true);
            mailSender.send(message);
            log.info("Email sent to {}", to);
        } catch (Exception e) {
            log.warn("Failed to send email to {}: {}", to, e.getMessage());
        }
    }

    private boolean isAllowedRecipient(String to) {
        if (!whitelistEnforced) {
            return true;
        }
        if (whitelist.isEmpty()) {
            return false;
        }
        String normalized = to.trim().toLowerCase();
        return whitelist.contains(normalized);
    }

    private Set<String> parseWhitelist(String raw) {
        if (raw == null || raw.isBlank()) {
            return Collections.emptySet();
        }
        Set<String> entries = new LinkedHashSet<>();
        for (String item : raw.split(",")) {
            String trimmed = item.trim().toLowerCase();
            if (!trimmed.isEmpty()) {
                entries.add(trimmed);
            }
        }
        return Collections.unmodifiableSet(entries);
    }

    private String toHtml(String body) {
        if (body == null) {
            return "";
        }
        return body.replace("\r\n", "\n").replace("\n", "<br>");
    }
}
