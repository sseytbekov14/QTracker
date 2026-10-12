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
     * dev keeps its conveniences. The profile itself defaults to dev (application.yml): the deployment must set
     * SPRING_PROFILES_ACTIVE (5_prod_checklist.md).
     */
    @Test
    void stageAndProd_turnOffTheDevConveniences_andNeedTheirSettingsGiven() {
        assertThat(value("spring.profiles.active")).isEqualTo("dev");
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
