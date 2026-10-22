package com.kpmg.qtracker.loadtest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The loadtest profile starts only on PostgreSQL on this computer, in a database whose name ends with _loadtest. */
class LoadTestDatabaseTest {

    @Test
    void aLocalLoadTestDatabase_isAccepted() {
        assertThat(LoadTestDatabase.refusal("jdbc:postgresql://localhost:5433/qtracker_loadtest")).isEmpty();
        assertThat(LoadTestDatabase.refusal("jdbc:postgresql://127.0.0.1/qtracker_loadtest?reWriteBatchedInserts=true"))
                .isEmpty();
        assertThat(LoadTestDatabase.refusal("jdbc:postgresql://[::1]:5432/QTracker_LoadTest")).isEmpty();
    }

    @Test
    void anotherName_isRefused() {
        assertThat(LoadTestDatabase.refusal("jdbc:postgresql://localhost:5432/QTracker"))
                .hasValueSatisfying(reason -> assertThat(reason).contains("does not end with _loadtest"));
        assertThat(LoadTestDatabase.refusal("jdbc:postgresql://localhost:5432/qtracker_prod")).isPresent();
        assertThat(LoadTestDatabase.refusal("jdbc:postgresql://localhost:5432/_loadtest")).isPresent();
        assertThat(LoadTestDatabase.refusal("jdbc:postgresql://localhost:5432/qtracker_loadtest_old")).isPresent();
    }

    @Test
    void anotherHost_isRefused() {
        assertThat(LoadTestDatabase.refusal("jdbc:postgresql://db.example:5432/qtracker_loadtest"))
                .hasValueSatisfying(reason -> assertThat(reason).contains("not on this computer"));
        assertThat(LoadTestDatabase.refusal("jdbc:postgresql://localhost,db.example/qtracker_loadtest"))
                .hasValueSatisfying(reason -> assertThat(reason).contains("several database hosts"));
        assertThat(LoadTestDatabase.refusal("jdbc:postgresql://localhost.example/qtracker_loadtest")).isPresent();
    }

    @Test
    void anotherKindOfDatabase_isRefused() {
        assertThat(LoadTestDatabase.refusal("jdbc:h2:mem:qtracker_loadtest")).isPresent();
        assertThat(LoadTestDatabase.refusal(null)).isPresent();
        assertThat(LoadTestDatabase.refusal("")).isPresent();
    }

    @Test
    void theGuard_stopsTheStartBeforeAnyBean() {
        LoadTestDatabaseGuard guard = new LoadTestDatabaseGuard();
        guard.setEnvironment(new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5432/QTracker"));
        assertThatThrownBy(() -> guard.postProcessBeanFactory(new DefaultListableBeanFactory()))
                .hasMessageContaining("does not end with _loadtest");

        MockEnvironment dev = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5433/qtracker_loadtest");
        dev.setActiveProfiles("dev", "loadtest");
        guard.setEnvironment(dev);
        assertThatThrownBy(() -> guard.postProcessBeanFactory(new DefaultListableBeanFactory()))
                .hasMessageContaining("with dev");

        MockEnvironment prod = new MockEnvironment()
                .withProperty("spring.datasource.url", "jdbc:postgresql://localhost:5433/qtracker_loadtest");
        prod.setActiveProfiles("prod", "loadtest");
        guard.setEnvironment(prod);
        assertThatCode(() -> guard.postProcessBeanFactory(new DefaultListableBeanFactory())).doesNotThrowAnyException();
    }
}
