package com.familyhub.demo.security;

import com.familyhub.demo.exception.TooManyAttemptsException;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Limits guessing at the family's sign-in password, the way parents' PINs are: five
 * wrong passwords for a username within 15 minutes lock that username for 15 minutes.
 * Counted per username, whether or not it exists, so the answer reveals nothing; a
 * right password clears the count. In memory: one server, and a restart forgetting the
 * count is harmless. A family locked out can still reset the password on the server.
 */
@Component
public class LoginAttemptLimiter {
    static final int MAX_FAILURES = 5;
    static final Duration WINDOW = Duration.ofMinutes(15);

    private final Clock clock;
    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();

    private record Attempts(int failures, Instant firstFailure, Instant lockedUntil) {}

    public LoginAttemptLimiter(Clock clock) {
        this.clock = clock;
    }

    /**
     * Refuses a sign-in while the username is locked.
     *
     * @throws TooManyAttemptsException saying how long is left (429)
     */
    public void checkAllowed(String username) {
        Attempts current = attempts.get(key(username));
        Instant now = Instant.now(clock);
        if (current != null && current.lockedUntil() != null && now.isBefore(current.lockedUntil())) {
            long minutes = Math.max(1, (Duration.between(now, current.lockedUntil()).toSeconds() + 59) / 60);
            throw new TooManyAttemptsException("Too many wrong passwords. Try again in " + minutes
                    + (minutes == 1 ? " minute" : " minutes")
                    + ", or reset it on the server (reset-password).");
        }
    }

    /** Counts a wrong password; the fifth within the window locks the username. */
    public void recordFailure(String username) {
        Instant now = Instant.now(clock);
        attempts.compute(key(username), (k, current) -> {
            boolean fresh = current == null || current.firstFailure().plus(WINDOW).isBefore(now)
                    || (current.lockedUntil() != null && !now.isBefore(current.lockedUntil()));
            int failures = fresh ? 1 : current.failures() + 1;
            Instant first = fresh ? now : current.firstFailure();
            return new Attempts(failures, first, failures >= MAX_FAILURES ? now.plus(WINDOW) : null);
        });
    }

    /** A right password: the count starts again. */
    public void recordSuccess(String username) {
        attempts.remove(key(username));
    }

    private static String key(String username) {
        return username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
    }
}
