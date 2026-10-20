package com.kpmg.qtracker.service.notification;

import com.kpmg.qtracker.entity.NotificationTemplateText;
import com.kpmg.qtracker.repository.NotificationTemplateTextRepository;
import jakarta.annotation.PostConstruct;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * Where Flyway is off (the tests: Hibernate makes the schema), fills notification_template with the texts of v5 as V20
 * does; a key already there is left as it is.
 */
@Component
@ConditionalOnProperty(prefix = "spring.flyway", name = "enabled", havingValue = "false")
public class NotificationTemplateSeeder {

    private final NotificationTemplateTextRepository repository;
    private final Clock clock;

    public NotificationTemplateSeeder(NotificationTemplateTextRepository repository, Clock clock) {
        this.repository = repository;
        this.clock = clock;
    }

    @PostConstruct
    void seed() {
        for (Template template : Template.values()) {
            if (repository.existsById(template.name())) {
                continue;
            }
            NotificationTemplateText text = new NotificationTemplateText();
            text.setTemplateKey(template.name());
            text.setSubject(template.defaultSubject());
            text.setBody(template.defaultBody());
            text.setUpdatedAt(LocalDateTime.now(clock));
            repository.save(text);
        }
    }
}
