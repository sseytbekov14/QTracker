package com.kpmg.qtracker.security;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class LoginAttemptServiceTest {

    private final LoginAttemptService service = new LoginAttemptService();

    @Test
    void userBecomesLockedAfterConfiguredNumberOfFailures() {
        for (int attempt = 1; attempt <= 4; attempt++) {
            service.recordFailure("user@example.com");
            assertThat(service.isLocked("user@example.com")).isFalse();
        }

        service.recordFailure("user@example.com");

        assertThat(service.isLocked("user@example.com")).isTrue();
    }

    @Test
    void lockExpiresAfterTimeout() throws Exception {
        for (int attempt = 1; attempt <= 5; attempt++) {
            service.recordFailure("user@example.com");
        }
        forceLockExpiry("user@example.com");

        assertThat(service.isLocked("user@example.com")).isFalse();
    }

    @Test
    void successfulLoginResetsFailureCount() {
        for (int attempt = 1; attempt <= 3; attempt++) {
            service.recordFailure("user@example.com");
        }

        service.recordSuccess("user@example.com");

        for (int attempt = 1; attempt <= 4; attempt++) {
            service.recordFailure("user@example.com");
        }

        assertThat(service.isLocked("user@example.com")).isFalse();
    }

    @SuppressWarnings("unchecked")
    private void forceLockExpiry(String username) throws Exception {
        Field attemptsField = LoginAttemptService.class.getDeclaredField("attempts");
        attemptsField.setAccessible(true);
        Map<String, Object> attempts = (Map<String, Object>) attemptsField.get(service);
        Object state = attempts.get(username.toLowerCase());

        Field lockUntilMillisField = state.getClass().getDeclaredField("lockUntilMillis");
        lockUntilMillisField.setAccessible(true);
        lockUntilMillisField.setLong(state, System.currentTimeMillis() - 1L);
    }
}