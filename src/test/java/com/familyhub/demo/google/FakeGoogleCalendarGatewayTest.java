package com.familyhub.demo.google;

import com.familyhub.demo.model.GoogleAccessRole;
import com.familyhub.demo.model.GoogleOAuthToken;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.CalendarListEntry;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The in-memory Google the sync and write tests run against (and the dev profile, so the
 * e2e suite can create events). It has to behave like Google where the hub depends on
 * it: sync tokens only on the last page, incremental lists of changes and deletions,
 * expired tokens, etags, access roles, shared-property lookups, single instances of a
 * series, and moves.
 */
class FakeGoogleCalendarGatewayTest {

    private static final String HUB = "hub@group.calendar.google.com";
    private static final String OTHER = "other@group.calendar.google.com";

    private FakeGoogleCalendarGateway google;
    private GoogleOAuthToken dad;
    private GoogleOAuthToken mom;

    @BeforeEach
    void setUp() {
        google = new FakeGoogleCalendarGateway();
        dad = account();
        mom = account();
        google.addCalendar(HUB, "Hub", "America/Los_Angeles");
        google.addCalendar(OTHER, "Other", "America/Los_Angeles");
        google.grant(dad, HUB, GoogleAccessRole.OWNER);
        google.grant(dad, OTHER, GoogleAccessRole.OWNER);
        google.grant(mom, HUB, GoogleAccessRole.READER);
    }

    private static GoogleOAuthToken account() {
        GoogleOAuthToken token = new GoogleOAuthToken();
        token.setId(UUID.randomUUID());
        return token;
    }

    private static EventDateTime at(String instant) {
        return new EventDateTime().setDateTime(new DateTime(instant)).setTimeZone("America/Los_Angeles");
    }

    private static Event timed(String title, String start, String end) {
        return new Event().setSummary(title).setStart(at(start)).setEnd(at(end));
    }

    /** Every page of a list, and the sync token that came with the last one. */
    private record Listed(List<Event> items, List<String> tokensPerPage) {
        String syncToken() {
            return tokensPerPage.getLast();
        }

        List<String> titles() {
            return items.stream().map(Event::getSummary).toList();
        }
    }

    private Listed listAll(GoogleOAuthToken account, String calendar, String syncToken, Instant timeMin) {
        List<Event> items = new ArrayList<>();
        List<String> tokens = new ArrayList<>();
        String page = null;
        do {
            GoogleCalendarGateway.EventPage result = google.listEvents(account, calendar, syncToken == null
                    ? GoogleCalendarGateway.ListRequest.fullSync(timeMin, page)
                    : GoogleCalendarGateway.ListRequest.incremental(syncToken, page));
            items.addAll(result.items());
            tokens.add(result.nextSyncToken());
            page = result.nextPageToken();
        } while (page != null);
        return new Listed(items, tokens);
    }

    @Test
    void aFullSyncPagesAndOnlyItsLastPageCarriesTheSyncToken() {
        google.setPageSize(1);
        Event created = google.insertEvent(dad, HUB, timed("One", "2026-12-03T17:00:00Z", "2026-12-03T18:00:00Z"),
                false);
        google.insertEvent(dad, HUB, timed("Two", "2026-12-04T17:00:00Z", "2026-12-04T18:00:00Z"), false);
        google.insertEvent(dad, HUB, timed("Three", "2026-12-05T17:00:00Z", "2026-12-05T18:00:00Z"), false);

        Listed full = listAll(dad, HUB, null, null);

        assertThat(full.titles()).containsExactly("One", "Two", "Three");
        assertThat(full.tokensPerPage()).hasSize(3);
        assertThat(full.tokensPerPage().subList(0, 2)).containsOnlyNulls();
        assertThat(full.syncToken()).isNotNull();
        assertThat(created.getId()).isNotBlank();
        assertThat(created.getEtag()).isNotBlank();
        assertThat(created.getICalUID()).isNotBlank();
        assertThat(created.getStatus()).isEqualTo("confirmed");
    }

    @Test
    void anIncrementalListHasOnlyWhatChangedDeletionsIncluded() {
        Event keep = google.insertEvent(dad, HUB, timed("Keep", "2026-12-03T17:00:00Z", "2026-12-03T18:00:00Z"),
                false);
        Event drop = google.insertEvent(dad, HUB, timed("Drop", "2026-12-04T17:00:00Z", "2026-12-04T18:00:00Z"),
                false);
        String token = listAll(dad, HUB, null, null).syncToken();

        google.updateEvent(dad, HUB, keep.getId(), keep.clone().setSummary("Kept"), keep.getEtag(), false);
        google.deleteEvent(dad, HUB, drop.getId(), drop.getEtag());
        Listed changes = listAll(dad, HUB, token, null);

        assertThat(changes.items()).extracting(Event::getId, Event::getStatus)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple(keep.getId(), "confirmed"),
                        org.assertj.core.groups.Tuple.tuple(drop.getId(), "cancelled"));
        assertThat(listAll(dad, HUB, changes.syncToken(), null).items()).isEmpty();
    }

    @Test
    void anExpiredSyncTokenAsksForAFullSync() {
        String token = listAll(dad, HUB, null, null).syncToken();
        google.expireSyncTokens();

        assertThatThrownBy(() -> google.listEvents(dad, HUB, GoogleCalendarGateway.ListRequest.incremental(token, null)))
                .isInstanceOfSatisfying(GoogleCalendarException.class, e ->
                        assertThat(e.getKind()).isEqualTo(GoogleCalendarException.Kind.SYNC_TOKEN_EXPIRED));
    }

    @Test
    void aStaleEtagIsAConflictAndAnUpdateChangesTheEtag() {
        Event event = google.insertEvent(dad, HUB, timed("Swim", "2026-12-03T17:00:00Z", "2026-12-03T18:00:00Z"),
                false);
        Event updated = google.updateEvent(dad, HUB, event.getId(), event.clone().setSummary("Swim lesson"),
                event.getEtag(), false);

        assertThat(updated.getEtag()).isNotEqualTo(event.getEtag());
        assertThatThrownBy(() -> google.updateEvent(dad, HUB, event.getId(), event.clone().setSummary("Late"),
                event.getEtag(), false))
                .isInstanceOfSatisfying(GoogleCalendarException.class, e ->
                        assertThat(e.getKind()).isEqualTo(GoogleCalendarException.Kind.CONFLICT));
        assertThatThrownBy(() -> google.deleteEvent(dad, HUB, event.getId(), event.getEtag()))
                .isInstanceOfSatisfying(GoogleCalendarException.class, e ->
                        assertThat(e.getKind()).isEqualTo(GoogleCalendarException.Kind.CONFLICT));
        assertThat(google.getEvent(dad, HUB, event.getId()).getSummary()).isEqualTo("Swim lesson");
    }

    @Test
    void accessRolesDecideWhoMayWriteAndWhatEachAccountSees() {
        assertThatThrownBy(() -> google.insertEvent(mom, HUB, timed("Mine", "2026-12-03T17:00:00Z",
                "2026-12-03T18:00:00Z"), false))
                .isInstanceOfSatisfying(GoogleCalendarException.class, e ->
                        assertThat(e.getKind()).isEqualTo(GoogleCalendarException.Kind.FORBIDDEN));
        assertThatThrownBy(() -> google.listEvents(mom, OTHER, GoogleCalendarGateway.ListRequest.fullSync(null, null)))
                .isInstanceOfSatisfying(GoogleCalendarException.class, e ->
                        assertThat(e.getKind()).isEqualTo(GoogleCalendarException.Kind.GONE));

        assertThat(google.listCalendars(mom)).extracting(CalendarListEntry::getId, CalendarListEntry::getAccessRole)
                .containsExactly(org.assertj.core.groups.Tuple.tuple(HUB, "reader"));
        assertThat(google.listCalendars(dad)).extracting(CalendarListEntry::getAccessRole)
                .containsOnly("owner");
    }

    @Test
    void aSharedPropertyFindsTheEventThatCarriesIt() {
        Event tagged = timed("Copied", "2026-12-03T17:00:00Z", "2026-12-03T18:00:00Z");
        tagged.setExtendedProperties(new Event.ExtendedProperties().setShared(Map.of("familyhubLegacyId", "abc")));
        google.insertEvent(dad, HUB, tagged, false);
        google.insertEvent(dad, HUB, timed("Plain", "2026-12-03T17:00:00Z", "2026-12-03T18:00:00Z"), false);

        assertThat(google.listEvents(dad, HUB,
                GoogleCalendarGateway.ListRequest.withSharedProperty("familyhubLegacyId", "abc")).items())
                .extracting(Event::getSummary).containsExactly("Copied");
        assertThat(google.listEvents(dad, HUB,
                GoogleCalendarGateway.ListRequest.withSharedProperty("familyhubLegacyId", "zzz")).items()).isEmpty();
    }

    @Test
    void oneOccurrenceOfASeriesCanBeChangedOrCancelledOnItsOwn() {
        Event weekly = timed("Class", "2026-11-06T17:00:00Z", "2026-11-06T18:00:00Z")
                .setRecurrence(List.of("RRULE:FREQ=WEEKLY;BYDAY=FR"));
        Event master = google.insertEvent(dad, HUB, weekly, false);
        String token = listAll(dad, HUB, null, null).syncToken();

        Event second = google.getInstance(dad, HUB, master.getId(), at("2026-11-13T17:00:00Z"));
        assertThat(second.getRecurringEventId()).isEqualTo(master.getId());
        assertThat(second.getOriginalStartTime().getDateTime().getValue())
                .isEqualTo(Instant.parse("2026-11-13T17:00:00Z").toEpochMilli());
        assertThat(second.getEnd().getDateTime().getValue())
                .isEqualTo(Instant.parse("2026-11-13T18:00:00Z").toEpochMilli());
        google.updateEvent(dad, HUB, second.getId(), second.clone().setSummary("Class (moved)")
                .setStart(at("2026-11-14T17:00:00Z")).setEnd(at("2026-11-14T18:00:00Z")), second.getEtag(), false);
        Event third = google.getInstance(dad, HUB, master.getId(), at("2026-11-20T17:00:00Z"));
        google.deleteEvent(dad, HUB, third.getId(), third.getEtag());

        Listed changes = listAll(dad, HUB, token, null);
        assertThat(changes.items()).extracting(Event::getSummary, Event::getStatus, Event::getRecurringEventId)
                .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("Class (moved)", "confirmed", master.getId()),
                        // Like Google, a cancelled occurrence carries no title or times.
                        org.assertj.core.groups.Tuple.tuple(null, "cancelled", master.getId()));
        Event cancelled = changes.items().stream().filter(e -> "cancelled".equals(e.getStatus())).findFirst()
                .orElseThrow();
        assertThat(cancelled.getStart()).isNull();
        assertThat(cancelled.getOriginalStartTime().getDateTime().getValue())
                .isEqualTo(Instant.parse("2026-11-20T17:00:00Z").toEpochMilli());
    }

    @Test
    void aMovedEventLeavesOneCalendarAndArrivesOnTheOther() {
        Event event = google.insertEvent(dad, HUB, timed("Dentist", "2026-12-03T17:00:00Z", "2026-12-03T18:00:00Z"),
                false);
        String hubToken = listAll(dad, HUB, null, null).syncToken();
        String otherToken = listAll(dad, OTHER, null, null).syncToken();

        Event moved = google.moveEvent(dad, HUB, event.getId(), OTHER);

        assertThat(moved.getId()).isEqualTo(event.getId());
        assertThat(listAll(dad, HUB, hubToken, null).items()).extracting(Event::getStatus).containsExactly("cancelled");
        assertThat(listAll(dad, OTHER, otherToken, null).titles()).containsExactly("Dentist");
    }

    @Test
    void aFullSyncFromADateSkipsOlderSinglesButKeepsSeries() {
        google.insertEvent(dad, HUB, timed("Old", "2024-01-05T17:00:00Z", "2024-01-05T18:00:00Z"), false);
        google.insertEvent(dad, HUB, timed("Old series", "2024-01-05T17:00:00Z", "2024-01-05T18:00:00Z")
                .setRecurrence(List.of("RRULE:FREQ=YEARLY")), false);
        google.insertEvent(dad, HUB, timed("New", "2026-12-03T17:00:00Z", "2026-12-03T18:00:00Z"), false);

        assertThat(listAll(dad, HUB, null, Instant.parse("2025-11-21T00:00:00Z")).titles())
                .containsExactlyInAnyOrder("Old series", "New");
    }

    @Test
    void aQueuedFailureFailsExactlyTheNextCall() {
        google.failNextWith(GoogleCalendarException.Kind.UNAVAILABLE);

        assertThatThrownBy(() -> google.listCalendars(dad))
                .isInstanceOfSatisfying(GoogleCalendarException.class, e ->
                        assertThat(e.getKind()).isEqualTo(GoogleCalendarException.Kind.UNAVAILABLE));
        assertThat(google.listCalendars(dad)).hasSize(2);
    }

    @Test
    void anEventEditedInGooglesOwnAppShowsUpAsAChange() {
        Event event = google.insertEvent(dad, HUB, timed("Party", "2026-12-03T17:00:00Z", "2026-12-03T18:00:00Z"),
                false);
        String token = listAll(dad, HUB, null, null).syncToken();

        google.editInGoogle(HUB, event.getId(), e -> e.setLocation("The park"));

        assertThat(listAll(dad, HUB, token, null).items()).extracting(Event::getLocation).containsExactly("The park");
    }
}
