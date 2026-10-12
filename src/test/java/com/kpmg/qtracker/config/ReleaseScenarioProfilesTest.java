package com.kpmg.qtracker.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.env.MutablePropertySources;
import org.springframework.core.env.PropertiesPropertySource;
import org.springframework.core.env.PropertySourcesPropertyResolver;
import org.springframework.core.io.ClassPathResource;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Release test pass: what each profile turns on when no environment variable is set (application.yml, then the
 * profile's file on top), read from the files the jar ships.
 */
class ReleaseScenarioProfilesTest {

    @Test
    void swagger_isOffByDefault_onlyTheDevProfileTurnsItOn() {
        assertThat(value("springdoc.api-docs.enabled")).isEqualTo("false");
        assertThat(value("springdoc.swagger-ui.enabled")).isEqualTo("false");
        assertThat(value("springdoc.api-docs.enabled", "stage")).isEqualTo("false");
        assertThat(value("springdoc.api-docs.enabled", "prod")).isEqualTo("false");
        assertThat(value("springdoc.api-docs.enabled", "dev")).isEqualTo("true");
        assertThat(value("springdoc.swagger-ui.enabled", "dev")).isEqualTo("true");
    }

    /**
     * What stage and prod run with when no environment variable is set: no seed people, no username login, no
     * e-mail, no Swagger, no SQL in the log, no fallback database or attachments folder (they must be given).
     * dev keeps its conveniences. There is no default profile (StartupGuard refuses to start without one).
     */
    @Test
    void stageAndProd_turnOffTheDevConveniences_andNeedTheirSettingsGiven() {
        assertThat(value("spring.profiles.active")).isNull();
        for (String profile : new String[]{"stage", "prod"}) {
            assertThat(value("dev.seed-users", profile)).as(profile).isEqualTo("false");
            assertThat(value("auth.username-login.enabled", profile)).as(profile).isEqualTo("false");
            assertThat(value("notifications.email.enabled", profile)).as(profile).isEqualTo("false");
            assertThat(value("spring.jpa.show-sql", profile)).as(profile).isEqualTo("false");
            assertThat(value("spring.jpa.hibernate.ddl-auto", profile)).as(profile).isEqualTo("validate");
            assertThat(value("spring.datasource.url", profile)).as(profile).isEqualTo("${SPRING_DATASOURCE_URL}");
            assertThat(value("file.upload.dir", profile)).as(profile).isEqualTo("${FILE_UPLOAD_DIR}");
            assertThat(value("app.base-url", profile)).as(profile).isEqualTo("${APP_BASE_URL}");
        }
        assertThat(value("auth.username-login.enabled", "dev")).isEqualTo("true");
        assertThat(value("spring.jpa.show-sql", "dev")).isEqualTo("true");
        assertThat(value("spring.datasource.password", "dev")).isEqualTo("postgres");
        assertThat(value("reminders.enabled")).isEqualTo("true");
        assertThat(value("controls.auto-create.enabled")).isEqualTo("true");
        assertThat(value("spring.servlet.multipart.max-file-size")).isEqualTo("10MB");
        assertThat(value("spring.servlet.multipart.max-request-size")).isEqualTo("100MB");
    }

    /**
     * Sign-in: prod with Microsoft Entra ID only, dev and stage with the password until their variables say
     * otherwise; Logout follows the mode unless set. X-Forwarded-* is not trusted unless a proxy is configured.
     */
    @Test
    void signIn_prodUsesSso_devAndStageThePassword_forwardedHeadersAreNotTrusted() {
        assertThat(value("auth.sso.enabled", "prod")).isEqualTo("true");
        assertThat(value("auth.password.enabled", "prod")).isEqualTo("false");
        for (String profile : new String[]{"dev", "stage"}) {
            assertThat(value("auth.sso.enabled", profile)).as(profile).isEqualTo("false");
            assertThat(value("auth.password.enabled", profile)).as(profile).isEqualTo("true");
        }
        assertThat(value("auth.logout.enabled", "prod")).isEmpty();
        assertThat(value("server.forward-headers-strategy")).isEqualTo("none");
        assertThat(value("server.forward-headers-strategy", "prod")).isEqualTo("none");
    }

    /** The rate limits come from RATE_LIMIT_* (the defaults are the limits used before). */
    @Test
    void rateLimits_comeFromTheEnvironment() {
        assertThat(value("rate-limit.window-seconds", "prod")).isEqualTo("60");
        assertThat(value("rate-limit.login", "prod")).isEqualTo("20");
        assertThat(value("rate-limit.api-write", "prod")).isEqualTo("120");
        assertThat(value("rate-limit.user-list", "prod")).isEqualTo("120");
        assertThat(value("rate-limit.heavy", "prod")).isEqualTo("20");
    }

    /** The property as the profile resolves it with no environment variables (placeholders at their default). */
    static String value(String key, String... profiles) {
        MutablePropertySources sources = new MutablePropertySources();
        for (int i = profiles.length - 1; i >= 0; i--) {
            sources.addLast(new PropertiesPropertySource(profiles[i], yaml("application-" + profiles[i] + ".yml")));
        }
        sources.addLast(new PropertiesPropertySource("application", yaml("application.yml")));
        PropertySourcesPropertyResolver resolver = new PropertySourcesPropertyResolver(sources);
        resolver.setIgnoreUnresolvableNestedPlaceholders(true);
        return resolver.getProperty(key);
    }

    private static Properties yaml(String name) {
        YamlPropertiesFactoryBean factory = new YamlPropertiesFactoryBean();
        factory.setResources(new ClassPathResource(name));
        Properties properties = factory.getObject();
        assertThat(properties).as(name).isNotNull();
        return properties;
    }
}
