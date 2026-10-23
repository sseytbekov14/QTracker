package com.kpmg.qtracker.service.notification;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checked on the sources: every in-app notification and every e-mail is made by NotificationProducer (so the rules of
 * NotificationRecipients - nothing to inactive, KDN or Read Only users - cannot be forgotten by a new way of sending),
 * e-mail leaves only through MailTransport, and the only schedules are the planned notifications and the outbox
 * dispatcher (NOTIFICATIONS_v5, stage 4).
 */
class NotificationsInOnePlaceTest {

    private static final Path MAIN = Path.of("src", "main", "java");

    private static List<String> filesContaining(String text) throws IOException {
        List<String> found = new ArrayList<>();
        try (Stream<Path> files = Files.walk(MAIN)) {
            for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                if (Files.readString(file, StandardCharsets.UTF_8).contains(text)) {
                    found.add(file.getFileName().toString());
                }
            }
        }
        return found;
    }

    @Test
    void onlyTheProducer_makesNotifications() throws IOException {
        assertThat(filesContaining("new Notification()")).containsExactly("NotificationProducer.java");
        assertThat(filesContaining("INSERT INTO email_outbox")).containsExactly("NotificationProducer.java");
    }

    @Test
    void onlyTheTransports_sendEmail() throws IOException {
        assertThat(filesContaining("JavaMailSender")).containsExactly("SmtpMailTransport.java");
    }

    @Test
    void theOnlySchedules_areThePlannedNotificationsAndTheDispatcher() throws IOException {
        assertThat(filesContaining("@Scheduled(")).containsExactlyInAnyOrder("PlannedNotificationTrigger.java",
                "EmailOutboxDispatchTrigger.java");
    }
}
