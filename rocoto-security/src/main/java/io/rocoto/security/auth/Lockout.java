package io.rocoto.security.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Brute-force protection. Failures are counted per key (user name and client address separately);
 * after {@code maxAttempts} failures within the window the key is locked for {@code lockDuration}.
 */
public final class Lockout {

    private static final int MAX_TRACKED_KEYS = 50_000;

    private record State(int failures, Instant windowStart, Instant lockedUntil) {
    }

    private final int maxAttempts;
    private final Duration window;
    private final Duration lockDuration;
    private final Clock clock;
    private final Map<String, State> states = new ConcurrentHashMap<>();

    public Lockout(int maxAttempts, Duration window, Duration lockDuration, Clock clock) {
        this.maxAttempts = maxAttempts;
        this.window = window;
        this.lockDuration = lockDuration;
        this.clock = clock;
    }

    public boolean isLocked(String key) {
        State s = states.get(key);
        return s != null && s.lockedUntil != null && clock.instant().isBefore(s.lockedUntil);
    }

    public void recordFailure(String key) {
        Instant now = clock.instant();
        if (states.size() > MAX_TRACKED_KEYS) {
            // Bound memory under a distributed attack: forget everything that is not currently locked.
            states.values().removeIf(s -> s.lockedUntil == null || now.isAfter(s.lockedUntil));
        }
        states.merge(key, new State(1, now, null), (old, fresh) -> {
            if (old.lockedUntil != null && now.isBefore(old.lockedUntil)) {
                return old;
            }
            boolean sameWindow = now.isBefore(old.windowStart.plus(window)) && old.lockedUntil == null;
            int failures = sameWindow ? old.failures + 1 : 1;
            Instant start = sameWindow ? old.windowStart : now;
            return new State(failures, start, failures >= maxAttempts ? now.plus(lockDuration) : null);
        });
    }

    public void recordSuccess(String key) {
        states.remove(key);
    }
}
