package com.kpmg.qtracker.config;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Profile;

import static org.assertj.core.api.Assertions.assertThat;

/** app.base-url on stage and prod: a warning (not a refusal) when the links of the e-mails would lead nowhere. */
class BaseUrlCheckTest {

    @Test
    void emptyOrLocalhost_isAProblem_aRealHostIsNot() {
        assertThat(BaseUrlCheck.problem(null)).contains("empty");
        assertThat(BaseUrlCheck.problem("  ")).contains("empty");
        assertThat(BaseUrlCheck.problem("http://localhost:8080")).contains("points to localhost");
        assertThat(BaseUrlCheck.problem("https://127.0.0.1")).contains("points to localhost");
        assertThat(BaseUrlCheck.problem("http://[::1]:8080")).contains("points to localhost");
        assertThat(BaseUrlCheck.problem("http://LOCALHOST")).contains("points to localhost");
        assertThat(BaseUrlCheck.problem("/view-control")).contains("not an absolute address");
        assertThat(BaseUrlCheck.problem("https://kzqtracker.go1.kworld.kpmg.com")).isEmpty();
        assertThat(BaseUrlCheck.problem("https://qtracker.example.test/")).isEmpty();
    }

    @Test
    void theWarning_namesTheValue_onStageAndProdOnly() {
        assertThat(BaseUrlCheck.class.getAnnotation(Profile.class).value()).containsExactlyInAnyOrder("stage", "prod");
        Logger logger = (Logger) LoggerFactory.getLogger(BaseUrlCheck.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            new BaseUrlCheck("http://localhost:8080").warnIfUnusable();
            new BaseUrlCheck("https://kzqtracker.go1.kworld.kpmg.com").warnIfUnusable();
        } finally {
            logger.detachAppender(appender);
        }
        assertThat(appender.list).singleElement().satisfies(event -> {
            assertThat(event.getLevel()).isEqualTo(Level.WARN);
            assertThat(event.getFormattedMessage()).contains("\"http://localhost:8080\"", "points to localhost");
        });
    }
}
