package com.familyhub.demo.google;

import com.familyhub.demo.config.GoogleCalendarProperties;
import com.familyhub.demo.model.GoogleOAuthToken;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.json.Json;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Date;
import java.util.Deque;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The real gateway sends Google exactly the requests the design relies on: masters not
 * expanded occurrences, deleted events included when syncing, a sync token never with
 * timeMin, If-Match on every change, no guest emails unless asked, and short timeouts.
 * Failures come back translated.
 */
class GoogleApiCalendarGatewayTest {

    private static final String HUB = "hub@group.calendar.google.com";

    /** One request as it reached the wire. */
    private record Call(String method, String url, String ifMatch, String authorization, String body,
                        int connectTimeout, int readTimeout) {
        String decodedUrl() {
            return URLDecoder.decode(url, StandardCharsets.UTF_8);
        }
    }

    /** Records every request and answers from a queue (200 {} when it runs dry). */
    private static final class RecordingTransport extends MockHttpTransport {
        final List<Call> calls = new ArrayList<>();
        final Deque<MockLowLevelHttpResponse> answers = new ArrayDeque<>();

        void answer(int status, String json) {
            answers.add(new MockLowLevelHttpResponse().setStatusCode(status)
                    .setContentType(Json.MEDIA_TYPE).setContent(json));
        }

        @Override
        public LowLevelHttpRequest buildRequest(String method, String url) {
            return new MockLowLevelHttpRequest(url) {
                private int connect;
                private int read;

                @Override
                public void setTimeout(int connectTimeout, int readTimeout) {
                    connect = connectTimeout;
                    read = readTimeout;
                }

                @Override
                public LowLevelHttpResponse execute() throws java.io.IOException {
                    calls.add(new Call(method, getUrl(), getFirstHeaderValue("If-Match"),
                            getFirstHeaderValue("Authorization"), getContentAsString(), connect, read));
                    return answers.isEmpty()
                            ? new MockLowLevelHttpResponse().setContentType(Json.MEDIA_TYPE).setContent("{}")
                            : answers.poll();
                }
            };
        }

        Call last() {
            return calls.getLast();
        }
    }

    private RecordingTransport transport;
    private GoogleApiCalendarGateway gateway;
    private GoogleOAuthToken account;

    @BeforeEach
    void setUp() {
        transport = new RecordingTransport();
        GoogleCredentialsProvider credentials = token -> GoogleCredentials.create(
                new AccessToken("test-access", Date.from(Instant.now().plusSeconds(3600))));
        gateway = new GoogleApiCalendarGateway(credentials, transport, new GoogleCalendarProperties(),
                new GoogleApiExceptionTranslator());
        account = new GoogleOAuthToken();
        account.setId(UUID.randomUUID());
    }

    @Test
    void aFullSyncAsksForMastersAndDeletionsFromItsStartWithShortTimeouts() {
        transport.answer(200, "{\"items\":[{\"id\":\"a\",\"summary\":\"Swim\"}],\"nextPageToken\":\"p2\"}");

        GoogleCalendarGateway.EventPage page = gateway.listEvents(account, HUB,
                GoogleCalendarGateway.ListRequest.fullSync(Instant.parse("2025-11-21T00:00:00Z"), null));

        Call call = transport.last();
        assertThat(call.method()).isEqualTo("GET");
        assertThat(call.decodedUrl()).contains("/calendars/" + HUB + "/events?")
                .contains("singleEvents=false").contains("showDeleted=true").contains("maxResults=2500")
                .contains("timeMin=2025-11-21T00:00:00").doesNotContain("syncToken");
        assertThat(call.authorization()).isEqualTo("Bearer test-access");
        assertThat(call.connectTimeout()).isEqualTo(5_000);
        assertThat(call.readTimeout()).isEqualTo(15_000);
        assertThat(page.items()).extracting(Event::getSummary).containsExactly("Swim");
        assertThat(page.nextPageToken()).isEqualTo("p2");
        assertThat(page.nextSyncToken()).isNull();
    }

    @Test
    void anIncrementalListSendsItsSyncTokenAndNoStartAndReturnsTheNextToken() {
        transport.answer(200, "{\"items\":[],\"nextSyncToken\":\"tok-2\"}");

        GoogleCalendarGateway.EventPage page = gateway.listEvents(account, HUB,
                GoogleCalendarGateway.ListRequest.incremental("tok-1", "p3"));

        assertThat(transport.last().decodedUrl()).contains("syncToken=tok-1").contains("pageToken=p3")
                .contains("singleEvents=false").doesNotContain("timeMin");
        assertThat(page.nextSyncToken()).isEqualTo("tok-2");
    }

    @Test
    void aSharedPropertyLookupFiltersOnItAndSkipsDeletedEvents() {
        gateway.listEvents(account, HUB,
                GoogleCalendarGateway.ListRequest.withSharedProperty("familyhubLegacyId", "abc"));

        assertThat(transport.last().decodedUrl()).contains("sharedExtendedProperty=familyhubLegacyId=abc")
                .doesNotContain("showDeleted=true");
    }

    @Test
    void changesCarryIfMatchAndSendNoGuestEmailsUnlessAsked() {
        Event event = new Event().setSummary("Swim lesson");

        gateway.updateEvent(account, HUB, "abc", event, "\"7\"", false);
        Call update = transport.last();
        assertThat(update.method()).isEqualTo("PUT");
        assertThat(update.decodedUrl()).contains("/events/abc").contains("sendUpdates=none");
        assertThat(update.ifMatch()).isEqualTo("\"7\"");
        assertThat(update.body()).contains("Swim lesson");

        gateway.deleteEvent(account, HUB, "abc", "\"8\"");
        assertThat(transport.last().method()).isEqualTo("DELETE");
        assertThat(transport.last().ifMatch()).isEqualTo("\"8\"");

        gateway.insertEvent(account, HUB, event, true);
        assertThat(transport.last().method()).isEqualTo("POST");
        assertThat(transport.last().decodedUrl()).contains("sendUpdates=all");
        assertThat(transport.last().ifMatch()).isNull();
    }

    @Test
    void anOccurrenceIsFoundByItsOriginalStartAndAMoveNamesTheDestination() {
        transport.answer(200, "{\"items\":[{\"id\":\"weekly_20261113T170000Z\",\"recurringEventId\":\"weekly\"}]}");

        Event instance = gateway.getInstance(account, HUB, "weekly",
                new EventDateTime().setDateTime(new DateTime("2026-11-13T17:00:00Z")));

        assertThat(transport.last().decodedUrl()).contains("/events/weekly/instances")
                .contains("originalStart=2026-11-13T17:00:00");
        assertThat(instance.getId()).isEqualTo("weekly_20261113T170000Z");

        transport.answer(200, "{\"items\":[]}");
        assertThatThrownBy(() -> gateway.getInstance(account, HUB, "weekly",
                new EventDateTime().setDate(new DateTime("2026-11-13"))))
                .isInstanceOfSatisfying(GoogleCalendarException.class, e ->
                        assertThat(e.getKind()).isEqualTo(GoogleCalendarException.Kind.GONE));
        assertThat(transport.last().decodedUrl()).contains("originalStart=2026-11-13");

        gateway.moveEvent(account, HUB, "abc", "other@group.calendar.google.com");
        assertThat(transport.last().method()).isEqualTo("POST");
        assertThat(transport.last().decodedUrl()).contains("/events/abc/move")
                .contains("destination=other@group.calendar.google.com").contains("sendUpdates=none");
    }

    @Test
    void theCalendarListIsReadAcrossPagesHiddenCalendarsIncluded() {
        transport.answer(200, "{\"items\":[{\"id\":\"a\",\"accessRole\":\"owner\"}],\"nextPageToken\":\"n\"}");
        transport.answer(200, "{\"items\":[{\"id\":\"b\",\"accessRole\":\"reader\"}]}");

        assertThat(gateway.listCalendars(account)).extracting("id").containsExactly("a", "b");
        assertThat(transport.calls).hasSize(2);
        assertThat(transport.calls.get(0).decodedUrl()).contains("showHidden=true");
        assertThat(transport.calls.get(1).decodedUrl()).contains("pageToken=n");
    }

    @Test
    void failuresComeBackTranslatedForTheKindOfCall() {
        transport.answer(412, "{\"error\":{\"code\":412,\"message\":\"Precondition Failed\","
                + "\"errors\":[{\"reason\":\"conditionNotMet\"}]}}");
        assertThatThrownBy(() -> gateway.updateEvent(account, HUB, "abc", new Event(), "\"1\"", false))
                .isInstanceOfSatisfying(GoogleCalendarException.class, e ->
                        assertThat(e.getKind()).isEqualTo(GoogleCalendarException.Kind.CONFLICT));

        transport.answer(410, "{\"error\":{\"code\":410,\"message\":\"Sync token is no longer valid\","
                + "\"errors\":[{\"reason\":\"fullSyncRequired\"}]}}");
        assertThatThrownBy(() -> gateway.listEvents(account, HUB,
                GoogleCalendarGateway.ListRequest.incremental("old", null)))
                .isInstanceOfSatisfying(GoogleCalendarException.class, e ->
                        assertThat(e.getKind()).isEqualTo(GoogleCalendarException.Kind.SYNC_TOKEN_EXPIRED));
    }
}
