package com.kpmg.qtracker.service;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

@Service
@Slf4j
@ConditionalOnProperty(prefix = "notifications.email", name = "enabled", havingValue = "true")
public class EmailNotificationChannel {

    private final JavaMailSender mailSender;
    private final String fromAddress;
    private final boolean whitelistEnforced;
    private final Set<String> whitelist;

    public EmailNotificationChannel(JavaMailSender mailSender,
                                    @Value("${notifications.email.from:}") String fromAddress,
                                    @Value("${notifications.email.whitelist:}") String whitelist,
                                    @Value("${notifications.email.whitelist-enforced:false}") boolean whitelistEnforced) {
        this.mailSender = mailSender;
        this.fromAddress = fromAddress;
        this.whitelistEnforced = whitelistEnforced;
        this.whitelist = parseWhitelist(whitelist);
    }

    public void send(String to, String subject, String body) {
        send(to, subject, body, null);
    }

    /**
     * @param link the notification's link to its control (Notification.link): a "Go to Control" button in the
     *             e-mail, before its closing words, and the address under it for a mail program that hides the
     *             button. Only an absolute address: without app.base-url the link is a path, useless in a mail
     */
    public void send(String to, String subject, String body, String link) {
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
            helper.setText(toHtml(body, link), true);
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

    /** The body is plain text people partly typed (comments, IDs, names): escaped, then its line breaks kept. */
    static String toHtml(String body, String link) {
        String text = body == null ? "" : body.replace("\r\n", "\n");
        if (!NotificationLinks.isAbsolute(link)) {
            return escape(text);
        }
        // Before "Thank you" / "Kind regards", where the texts say "the button below"; at the end without them
        int closing = closingWordsAt(text);
        String before = closing < 0 ? text : text.substring(0, closing);
        String after = closing < 0 ? "" : text.substring(closing);
        return escape(before.stripTrailing()) + button(link) + escape(after);
    }

    private static int closingWordsAt(String text) {
        int at = 0;
        for (String line : text.split("\n", -1)) {
            String start = line.strip();
            if (start.startsWith("Thank you") || start.startsWith("Kind regards")) {
                return at;
            }
            at += line.length() + 1;
        }
        return -1;
    }

    private static String escape(String text) {
        return HtmlUtils.htmlEscape(text, StandardCharsets.UTF_8.name()).replace("\n", "<br>");
    }

    /** A table cell, as Outlook draws it; the address once more under it, as text and as a link. */
    private static String button(String link) {
        String href = HtmlUtils.htmlEscape(link, StandardCharsets.UTF_8.name());
        return "<table role=\"presentation\" cellspacing=\"0\" cellpadding=\"0\" border=\"0\" style=\"margin:16px 0 8px\">"
                + "<tr><td style=\"background:#00338D;border-radius:4px\">"
                + "<a href=\"" + href + "\" style=\"display:inline-block;padding:10px 22px;font-family:Arial,sans-serif;"
                + "font-size:14px;font-weight:bold;color:#ffffff;text-decoration:none\">"
                + NotificationLinks.BUTTON_LABEL + "</a></td></tr></table>"
                + "<p style=\"margin:0 0 16px;font-family:Arial,sans-serif;font-size:12px;color:#666666\">"
                + "If the button does not open, copy this address into the browser:<br>"
                + "<a href=\"" + href + "\" style=\"color:#00338D\">" + href + "</a></p>";
    }
}
