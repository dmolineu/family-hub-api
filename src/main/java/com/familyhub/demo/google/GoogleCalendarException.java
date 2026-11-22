package com.familyhub.demo.google;

import lombok.Getter;

import java.time.Duration;
import java.util.Optional;

/**
 * A Google Calendar call that failed, reduced to what the caller has to decide on. The
 * sync and the write path act on {@link #getKind()}; the API maps kinds to its own error
 * codes, and never to 401, because the app signs itself out on a 401.
 */
@Getter
public class GoogleCalendarException extends RuntimeException {

    /** What went wrong, from the caller's point of view. */
    public enum Kind {
        /** A list's sync token is no longer valid: clear it and do a full sync. */
        SYNC_TOKEN_EXPIRED,
        /** The event does not exist any more (404, or 410 on one event). */
        GONE,
        /** The event changed since its etag was read (412). */
        CONFLICT,
        /** Google asked us to slow down (429, or a 403 rate-limit reason). */
        RATE_LIMITED,
        /** The account may not do this on that calendar (other 403s). */
        FORBIDDEN,
        /** Google refused the request as invalid (400); the message says why. */
        REJECTED,
        /** The account must sign in again (401, or a revoked or expired refresh token). */
        REAUTH_REQUIRED,
        /** Google could not be reached or failed on its side (5xx, network). */
        UNAVAILABLE
    }

    private final Kind kind;
    private final Duration retryAfterValue;

    /**
     * A translated failure.
     *
     * @param kind       what went wrong
     * @param message    Google's message, or a description of the failure
     * @param retryAfter how long Google asked us to wait, or null
     * @param cause      the original exception, or null
     */
    public GoogleCalendarException(Kind kind, String message, Duration retryAfter, Throwable cause) {
        super(message, cause);
        this.kind = kind;
        this.retryAfterValue = retryAfter;
    }

    /**
     * How long Google asked callers to wait before trying again.
     *
     * @return the Retry-After duration, when Google sent one
     */
    public Optional<Duration> getRetryAfter() {
        return Optional.ofNullable(retryAfterValue);
    }
}
