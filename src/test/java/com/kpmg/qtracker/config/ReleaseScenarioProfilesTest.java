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
