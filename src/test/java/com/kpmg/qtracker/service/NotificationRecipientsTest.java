package com.kpmg.qtracker.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The role KDN gets no notification (AccessPolicy.hasNotifications, business decision of 2026-10-14): every service
 * that makes notifications finds its recipients through NotificationService.receives, so a new way of sending cannot
 * forget the rule. Checked on the sources, next to the behaviour tests (NotificationServiceTest,
 * MonthlyDay3Day6ReminderServiceTest, KdnNotificationsIT).
 */
class NotificationRecipientsTest {

    private static final Path SERVICES = Path.of("src", "main", "java", "com", "kpmg", "qtracker", "service");

    @Test
    void everyRecipientLookupOfTheNotificationServices_goesThroughReceives() throws IOException {
        List<String> unfiltered = new ArrayList<>();
        int lookups = 0;
        try (Stream<Path> files = Files.list(SERVICES)) {
            for (Path file : files.filter(p -> p.getFileName().toString().endsWith("NotificationService.java")).toList()) {
                String source = Files.readString(file, StandardCharsets.UTF_8);
                // A notification is saved or an e-mail sent: then every user it looks up must be filtered
                if (!source.contains("notificationRepository.save") && !source.contains("emailChannel.send")) {
                    continue;
                }
                Matcher lookup = Pattern.compile("userRepository\\.findByMail\\([^;]*").matcher(source);
                while (lookup.find()) {
                    lookups++;
                    if (!lookup.group().contains(".filter(NotificationService::receives)")) {
                        unfiltered.add(file.getFileName() + ": " + lookup.group());
                    }
                }
            }
        }
        // 21 since the in-app copies of ON_BEHALF and Shared With are no longer made (NOTIFICATIONS_v5)
        assertThat(lookups).as("recipient lookups found").isGreaterThanOrEqualTo(21);
        assertThat(unfiltered).isEmpty();
    }
}
