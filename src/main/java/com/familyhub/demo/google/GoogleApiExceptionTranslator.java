package com.familyhub.demo.google;

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpResponseException;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.Set;

/**
 * Turns whatever a Google Calendar call threw into a {@link GoogleCalendarException}, so
 * callers decide on a {@link GoogleCalendarException.Kind} and never on raw status codes.
 */
@Component
public class GoogleApiExceptionTranslator {

    /** Which kind of call failed: a 410 means different things to each. */
    public enum Operation {
        /** events.list, with or without a sync token. */
        LIST_EVENTS,
        /** A call on one event: get, insert, update, delete, instances, move. */
        EVENT,
        /** calendarList.list. */
        CALENDAR_LIST
    }

    // Google reports quota and rate limits as 403 with one of these reasons; any other 403
    // is a real permission problem.
    private static final Set<String> RATE_LIMIT_REASONS =
            Set.of("rateLimitExceeded", "userRateLimitExceeded", "quotaExceeded", "dailyLimitExceeded");

    /**
     * Translates a failure.
     *
     * @param failure   what the call threw
     * @param operation which kind of call it was
     * @return the translated failure; an already translated one is returned as it is
     */
    public GoogleCalendarException translate(Exception failure, Operation operation) {
        if (failure instanceof GoogleCalendarException translated) {
            return translated;
        }
        if (failure instanceof HttpResponseException http) {
            return fromStatus(http, operation);
        }
        if (failure.getMessage() != null && failure.getMessage().contains("invalid_grant")) {
            // google-auth-library's refresh failure when the grant was revoked or expired.
            return new GoogleCalendarException(GoogleCalendarException.Kind.REAUTH_REQUIRED,
                    "Google access was revoked or expired", null, failure);
        }
        return new GoogleCalendarException(GoogleCalendarException.Kind.UNAVAILABLE,
                "Google Calendar could not be reached: " + failure, null, failure);
    }

    private GoogleCalendarException fromStatus(HttpResponseException http, Operation operation) {
        int status = http.getStatusCode();
        String message = googleMessage(http);
        GoogleCalendarException.Kind kind = switch (status) {
            case 400 -> GoogleCalendarException.Kind.REJECTED;
            case 401 -> GoogleCalendarException.Kind.REAUTH_REQUIRED;
            case 403 -> isRateLimit(http)
                    ? GoogleCalendarException.Kind.RATE_LIMITED
                    : GoogleCalendarException.Kind.FORBIDDEN;
            case 404 -> GoogleCalendarException.Kind.GONE;
            case 410 -> operation == Operation.LIST_EVENTS
                    ? GoogleCalendarException.Kind.SYNC_TOKEN_EXPIRED
                    : GoogleCalendarException.Kind.GONE;
            case 412 -> GoogleCalendarException.Kind.CONFLICT;
            case 429 -> GoogleCalendarException.Kind.RATE_LIMITED;
            default -> GoogleCalendarException.Kind.UNAVAILABLE;
        };
        Duration retryAfter = kind == GoogleCalendarException.Kind.RATE_LIMITED ? retryAfter(http) : null;
        return new GoogleCalendarException(kind, "Google answered " + status + ": " + message, retryAfter, http);
    }

    private static String googleMessage(HttpResponseException http) {
        if (http instanceof GoogleJsonResponseException json && json.getDetails() != null
                && json.getDetails().getMessage() != null) {
            return json.getDetails().getMessage();
        }
        return http.getStatusMessage();
    }

    private static boolean isRateLimit(HttpResponseException http) {
        return http instanceof GoogleJsonResponseException json && json.getDetails() != null
                && json.getDetails().getErrors() != null
                && json.getDetails().getErrors().stream()
                        .map(GoogleJsonError.ErrorInfo::getReason)
                        .anyMatch(reason -> reason != null && RATE_LIMIT_REASONS.contains(reason));
    }

    private static Duration retryAfter(HttpResponseException http) {
        if (http.getHeaders() == null) {
            return null;
        }
        Object value = http.getHeaders().get("retry-after");
        String seconds = value instanceof Iterable<?> values && values.iterator().hasNext()
                ? String.valueOf(values.iterator().next())
                : value == null ? null : String.valueOf(value);
        if (seconds == null) {
            return null;
        }
        try {
            return Duration.ofSeconds(Long.parseLong(seconds.trim()));
        } catch (NumberFormatException e) {
            // Retry-After may also be an HTTP date; the caller's own backoff applies then.
            return null;
        }
    }
}
