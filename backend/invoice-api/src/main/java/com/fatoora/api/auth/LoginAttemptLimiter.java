package com.fatoora.api.auth;

import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;

/** Locks an email + IP pair for 15 minutes after 5 failed logins. In-memory: fine for a single instance. */
@Component
public class LoginAttemptLimiter {

    private static final int MAX_FAILURES = 5;
    private static final Duration LOCK = Duration.ofMinutes(15);

    private record Attempts(int failures, Instant lockedUntil) {}

    private final ConcurrentHashMap<String, Attempts> attempts = new ConcurrentHashMap<>();

    public boolean isLocked(String key) {
        Attempts a = attempts.get(key);
        return a != null && a.lockedUntil() != null && a.lockedUntil().isAfter(Instant.now());
    }

    public void failed(String key) {
        attempts.compute(key, (k, a) -> {
            int failures = (a == null || (a.lockedUntil() != null && a.lockedUntil().isBefore(Instant.now())))
                    ? 1 : a.failures() + 1;
            return new Attempts(failures, failures >= MAX_FAILURES ? Instant.now().plus(LOCK) : null);
        });
    }

    public void succeeded(String key) {
        attempts.remove(key);
    }
}
