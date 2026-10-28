package com.familyhub.demo.security;

import com.familyhub.demo.exception.TooManyAttemptsException;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LoginAttemptLimiterTest {

    private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-10-27T18:00:00Z"));
    private final Clock clock = new Clock() {
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    };
    private final LoginAttemptLimiter limiter = new LoginAttemptLimiter(clock);

    private void fail(String username, int times) {
        for (int i = 0; i < times; i++) {
            limiter.recordFailure(username);
        }
    }

    @Test
    void fourWrongPasswordsAreFineAndTheFifthLocksTheUsername() {
        fail("molineus", 4);
        assertThatCode(() -> limiter.checkAllowed("molineus")).doesNotThrowAnyException();

        fail("molineus", 1);

        assertThatThrownBy(() -> limiter.checkAllowed("molineus"))
                .isInstanceOf(TooManyAttemptsException.class)
                .hasMessageContaining("15 minutes")
                .hasMessageContaining("reset-password");
        // Another family is unaffected.
        assertThatCode(() -> limiter.checkAllowed("smiths")).doesNotThrowAnyException();
    }

    @Test
    void theLockLiftsAfter15Minutes() {
        fail("molineus", 5);
        now.set(now.get().plus(Duration.ofMinutes(14)));
        assertThatThrownBy(() -> limiter.checkAllowed("molineus"))
                .isInstanceOf(TooManyAttemptsException.class)
                .hasMessageContaining("1 minute");
        now.set(now.get().plus(Duration.ofMinutes(1)));
        assertThatCode(() -> limiter.checkAllowed("molineus")).doesNotThrowAnyException();
    }

    @Test
    void aRightPasswordClearsTheCount() {
        fail("molineus", 4);
        limiter.recordSuccess("molineus");
        fail("molineus", 4);
        assertThatCode(() -> limiter.checkAllowed("molineus")).doesNotThrowAnyException();
    }

    @Test
    void oldMistakesAreForgotten() {
        fail("molineus", 4);
        now.set(now.get().plus(Duration.ofMinutes(16)));
        fail("molineus", 4);
        assertThatCode(() -> limiter.checkAllowed("molineus")).doesNotThrowAnyException();
    }

    @Test
    void usernamesMatchWhateverTheirCase() {
        fail("Molineus", 3);
        fail("MOLINEUS ", 2);
        assertThatThrownBy(() -> limiter.checkAllowed("molineus")).isInstanceOf(TooManyAttemptsException.class);
    }
}
