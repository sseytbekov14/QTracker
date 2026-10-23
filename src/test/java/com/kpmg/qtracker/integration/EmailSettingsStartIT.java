package com.kpmg.qtracker.integration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ApplicationContext;
import org.springframework.test.context.ActiveProfiles;

import static org.assertj.core.api.Assertions.assertThat;

/** E-mail on without NOTIFICATIONS_EMAIL_FROM (2026-10-22): the application starts, with a WARN that says so. */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:email-settings-start-it;MODE=PostgreSQL;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "notifications.scheduler.enabled=false",
        "notifications.dispatcher.enabled=false",
        "notifications.email.enabled=true",
        "notifications.email.from=",
        "file.upload.dir=target/it-uploads-email-settings"
})
@ActiveProfiles("test")
@ExtendWith(OutputCaptureExtension.class)
class EmailSettingsStartIT {

    @Autowired
    private ApplicationContext context;

    @Test
    void emailOnWithoutFrom_starts_andWarns(CapturedOutput output) {
        assertThat(context).isNotNull();
        assertThat(output.getAll()).contains("WARN").contains("NOTIFICATIONS_EMAIL_FROM is empty");
    }
}
