package com.familyhub.demo.exception;

import lombok.Getter;
import org.springframework.http.HttpStatus;

import java.time.Duration;
import java.util.Optional;

/**
 * A calendar write Google refused or could not take (Phase 4), with a code the app turns
 * into a message. No code is ever 401: the app signs itself out on a 401, and a Google
 * problem must never do that.
 */
@Getter
public class CalendarWriteException extends RuntimeException {

    /** Why the write did not happen. */
    public enum Code {
        /** No calendar to write to: the family has no default and none was named. */
        GOOGLE_NOT_CONNECTED(HttpStatus.CONFLICT),
        /** No signed-in account may write to that calendar. */
        GOOGLE_NO_WRITE_ACCESS(HttpStatus.FORBIDDEN),
        /** The account must sign in to Google again (refused, or without the write scope). */
        GOOGLE_REAUTH_REQUIRED(HttpStatus.FORBIDDEN),
        /** The event changed in Google since it was read; reload and try again. */
        EVENT_CONFLICT(HttpStatus.CONFLICT),
        /** The event is no longer in Google. */
        EVENT_GONE(HttpStatus.NOT_FOUND),
        /** Google refused the event as invalid; the message says why. */
        GOOGLE_REJECTED(HttpStatus.BAD_REQUEST),
        /** Google could not be reached or failed; try again later. */
        GOOGLE_UNAVAILABLE(HttpStatus.SERVICE_UNAVAILABLE),
        /** Google asked the hub to slow down; try again after Retry-After. */
        GOOGLE_RATE_LIMITED(HttpStatus.SERVICE_UNAVAILABLE);

        private final HttpStatus status;

        Code(HttpStatus status) {
            this.status = status;
        }

        /**
         * The HTTP status the API answers with.
         *
         * @return the status
         */
        public HttpStatus status() {
            return status;
        }
    }

    private final Code code;
    private final Duration retryAfterValue;

    /**
     * A refused write.
     *
     * @param code       why
     * @param message    a message for the person who tried
     * @param retryAfter how long to wait before trying again, or null
     */
    public CalendarWriteException(Code code, String message, Duration retryAfter) {
        super(message);
        this.code = code;
        this.retryAfterValue = retryAfter;
    }

    /**
     * The HTTP status the API answers with.
     *
     * @return the code's status
     */
    public HttpStatus getStatus() {
        return code.status();
    }

    /**
     * How long to wait before trying again.
     *
     * @return the wait, when Google gave one
     */
    public Optional<Duration> getRetryAfter() {
        return Optional.ofNullable(retryAfterValue);
    }
}
