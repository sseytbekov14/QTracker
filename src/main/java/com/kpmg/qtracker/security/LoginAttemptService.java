package com.kpmg.qtracker.security;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class LoginAttemptService {

    private static final int MAX_FAILED_ATTEMPTS = 5;
    private static final Duration LOCKOUT_DURATION = Duration.ofMinutes(15);

    private final Map<String, AttemptState> attempts = new ConcurrentHashMap<>();

    public boolean isLocked(String username) {
        if (username == null || username.isBlank()) {
            return false;
        }
        AttemptState state = attempts.get(normalize(username));
        if (state == null) {
            return false;
        }
        long now = System.currentTimeMillis();
        if (state.lockUntilMillis > now) {
            return true;
        }
        if (state.lockUntilMillis > 0 && state.lockUntilMillis <= now) {
            attempts.remove(normalize(username));
        }
        return false;
    }

    public void recordFailure(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        String key = normalize(username);
        attempts.compute(key, (k, existing) -> {
            AttemptState state = existing == null ? new AttemptState() : existing;
            long now = System.currentTimeMillis();
            if (state.lockUntilMillis > 0 && state.lockUntilMillis <= now) {
                state.failedAttempts = 0;
                state.lockUntilMillis = 0;
            }
            state.failedAttempts++;
            if (state.failedAttempts >= MAX_FAILED_ATTEMPTS) {
                state.lockUntilMillis = now + LOCKOUT_DURATION.toMillis();
            }
            return state;
        });
    }

    public void recordSuccess(String username) {
        if (username == null || username.isBlank()) {
            return;
        }
        attempts.remove(normalize(username));
    }

    private String normalize(String username) {
        return username.trim().toLowerCase();
    }

    private static final class AttemptState {
        private int failedAttempts;
        private long lockUntilMillis;
    }
}
