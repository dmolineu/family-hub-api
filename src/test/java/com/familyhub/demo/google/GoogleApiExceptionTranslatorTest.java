package com.familyhub.demo.google;

import com.google.api.client.googleapis.json.GoogleJsonError;
import com.google.api.client.googleapis.json.GoogleJsonResponseException;
import com.google.api.client.http.HttpHeaders;
import com.google.api.client.http.HttpResponseException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.List;

import static com.familyhub.demo.google.GoogleApiExceptionTranslator.Operation.CALENDAR_LIST;
import static com.familyhub.demo.google.GoogleApiExceptionTranslator.Operation.EVENT;
import static com.familyhub.demo.google.GoogleApiExceptionTranslator.Operation.LIST_EVENTS;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Every way a Google call can fail ends up as one {@link GoogleCalendarException.Kind},
 * so the sync and the write path decide on kinds, never on raw status codes. A 410 means
 * different things to a list (the sync token expired) and to one event (it is gone).
 */
class GoogleApiExceptionTranslatorTest {

    private final GoogleApiExceptionTranslator translator = new GoogleApiExceptionTranslator();

    private static GoogleJsonResponseException google(int status, String reason, String message) {
        return google(status, reason, message, new HttpHeaders());
    }

    private static GoogleJsonResponseException google(int status, String reason, String message,
                                                      HttpHeaders headers) {
        GoogleJsonError details = new GoogleJsonError();
        details.setCode(status);
        details.setMessage(message);
        if (reason != null) {
            GoogleJsonError.ErrorInfo info = new GoogleJsonError.ErrorInfo();
            info.setReason(reason);
            info.setMessage(message);
            details.setErrors(List.of(info));
        }
        return new GoogleJsonResponseException(
                new HttpResponseException.Builder(status, "status " + status, headers), details);
    }

    private GoogleCalendarException.Kind kind(Exception e, GoogleApiExceptionTranslator.Operation operation) {
        return translator.translate(e, operation).getKind();
    }

    @Test
    void a410IsAnExpiredSyncTokenForAListAndAGoneEventOtherwise() {
        assertThat(kind(google(410, "fullSyncRequired", "Sync token is no longer valid"), LIST_EVENTS))
                .isEqualTo(GoogleCalendarException.Kind.SYNC_TOKEN_EXPIRED);
        assertThat(kind(google(410, "deleted", "Resource has been deleted"), EVENT))
                .isEqualTo(GoogleCalendarException.Kind.GONE);
        assertThat(kind(google(404, "notFound", "Not Found"), EVENT))
                .isEqualTo(GoogleCalendarException.Kind.GONE);
    }

    @Test
    void aFailedEtagIsAConflict() {
        assertThat(kind(google(412, "conditionNotMet", "Precondition Failed"), EVENT))
                .isEqualTo(GoogleCalendarException.Kind.CONFLICT);
    }

    @Test
    void rateLimitsAreToldApartFromForbiddenAndKeepRetryAfter() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Retry-After", "120");
        GoogleCalendarException limited = translator.translate(
                google(403, "rateLimitExceeded", "Rate Limit Exceeded", headers), EVENT);
        assertThat(limited.getKind()).isEqualTo(GoogleCalendarException.Kind.RATE_LIMITED);
        assertThat(limited.getRetryAfter()).contains(Duration.ofSeconds(120));

        assertThat(kind(google(403, "userRateLimitExceeded", "User Rate Limit Exceeded"), LIST_EVENTS))
                .isEqualTo(GoogleCalendarException.Kind.RATE_LIMITED);
        assertThat(kind(google(429, null, "Too Many Requests"), CALENDAR_LIST))
                .isEqualTo(GoogleCalendarException.Kind.RATE_LIMITED);
        assertThat(translator.translate(google(429, null, "Too Many Requests"), EVENT).getRetryAfter())
                .isEmpty();

        assertThat(kind(google(403, "forbidden", "You need to have writer access to this calendar."), EVENT))
                .isEqualTo(GoogleCalendarException.Kind.FORBIDDEN);
    }

    @Test
    void aRejectedRequestKeepsGooglesMessage() {
        GoogleCalendarException rejected = translator.translate(
                google(400, "timeRangeEmpty", "The specified time range is empty."), EVENT);
        assertThat(rejected.getKind()).isEqualTo(GoogleCalendarException.Kind.REJECTED);
        assertThat(rejected.getMessage()).contains("The specified time range is empty.");
    }

    @Test
    void anUnauthorisedCallOrARevokedGrantNeedsTheAccountSignedInAgain() {
        assertThat(kind(google(401, "authError", "Invalid Credentials"), LIST_EVENTS))
                .isEqualTo(GoogleCalendarException.Kind.REAUTH_REQUIRED);
        // What google-auth-library throws when the refresh token was revoked or expired.
        assertThat(kind(new IOException("Error getting access token: 400 Bad Request "
                + "{\"error\": \"invalid_grant\", \"error_description\": \"Token has been expired or revoked.\"}"),
                EVENT)).isEqualTo(GoogleCalendarException.Kind.REAUTH_REQUIRED);
    }

    @Test
    void serverErrorsAndNetworkFailuresMeanGoogleIsUnavailable() {
        assertThat(kind(google(503, "backendError", "Backend Error"), EVENT))
                .isEqualTo(GoogleCalendarException.Kind.UNAVAILABLE);
        assertThat(kind(google(500, null, "Internal"), LIST_EVENTS))
                .isEqualTo(GoogleCalendarException.Kind.UNAVAILABLE);
        assertThat(kind(new SocketTimeoutException("Read timed out"), EVENT))
                .isEqualTo(GoogleCalendarException.Kind.UNAVAILABLE);
    }

    @Test
    void anAlreadyTranslatedFailurePassesThroughUnchanged() {
        GoogleCalendarException conflict = new GoogleCalendarException(GoogleCalendarException.Kind.CONFLICT,
                "etag", null, null);
        assertThat(translator.translate(conflict, EVENT)).isSameAs(conflict);
    }
}
