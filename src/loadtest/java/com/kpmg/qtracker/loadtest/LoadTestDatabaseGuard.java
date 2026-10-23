package com.kpmg.qtracker.loadtest;

import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.context.EnvironmentAware;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * The loadtest profile does not start on any database but a local *_loadtest one (LoadTestDatabase), nor together
 * with a profile that lets QTracker write to real data on its own (dev: the seed accounts). Runs before any bean
 * is made, so a refused start never opens a connection: no Flyway, no generator.
 */
@Component
@Profile("loadtest")
public class LoadTestDatabaseGuard implements BeanFactoryPostProcessor, EnvironmentAware {

    private Environment environment;

    @Override
    public void setEnvironment(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
        if (Arrays.asList(environment.getActiveProfiles()).contains("dev")) {
            throw new IllegalStateException("The loadtest profile does not start with dev: use prod,loadtest");
        }
        LoadTestDatabase.refusal(environment.getProperty("spring.datasource.url")).ifPresent(reason -> {
            throw new IllegalStateException("The loadtest profile does not start: " + reason);
        });
    }
}
