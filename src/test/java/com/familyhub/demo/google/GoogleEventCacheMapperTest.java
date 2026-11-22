package com.familyhub.demo.google;

import com.familyhub.demo.model.GoogleCalendar;
import com.familyhub.demo.model.GoogleEvent;
import com.familyhub.demo.model.GoogleEventStatus;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.services.calendar.model.Event;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Google's event becomes a cache row as Google sent it: timed events as instants with
 * the event's own zone, all-day events as dates with Google's exclusive end, series with
 * their rule lines verbatim, and the whole event kept as JSON so an update can send back
 * what the hub does not model. Members come from {@link MemberAttribution}.
 */
class GoogleEventCacheMapperTest {

    private static final UUID MAYA = UUID.randomUUID();

    private final GoogleEventCacheMapper mapper = new GoogleEventCacheMapper();
    private final GoogleCalendar hub = new GoogleCalendar();

    private static Event parse(String json) throws IOException {
        return GsonFactory.getDefaultInstance().fromString(json, Event.class);
    }

    @Test
    void aTimedEventKeepsItsInstantsZoneAndEveryFieldItShows() throws IOException {
        Event event = parse("""
                {"id":"abc","iCalUID":"abc@google.com","etag":"\\"3\\"","status":"confirmed",
                 "summary":"Swim","description":"Bring goggles","location":"Pool",
                 "htmlLink":"https://calendar.google.com/event?eid=abc",
                 "start":{"dateTime":"2026-12-03T09:00:00-08:00","timeZone":"America/Los_Angeles"},
                 "end":{"dateTime":"2026-12-03T10:00:00-08:00","timeZone":"America/Los_Angeles"},
                 "organizer":{"email":"dad@example.com","self":true},
                 "updated":"2026-11-21T18:00:00.000Z"}""");

        GoogleEvent row = mapper.toCache(event, hub, new UUID[]{MAYA}, new GoogleEvent());

        assertThat(row.getCalendar()).isSameAs(hub);
        assertThat(row.getGoogleEventId()).isEqualTo("abc");
        assertThat(row.getIcalUid()).isEqualTo("abc@google.com");
        assertThat(row.getEtag()).isEqualTo("\"3\"");
        assertThat(row.getStatus()).isEqualTo(GoogleEventStatus.CONFIRMED);
        assertThat(row.getTitle()).isEqualTo("Swim");
        assertThat(row.getDescription()).isEqualTo("Bring goggles");
        assertThat(row.getLocation()).isEqualTo("Pool");
        assertThat(row.getHtmlLink()).isEqualTo("https://calendar.google.com/event?eid=abc");
        assertThat(row.isAllDay()).isFalse();
        assertThat(row.getStartAt()).isEqualTo(Instant.parse("2026-12-03T17:00:00Z"));
        assertThat(row.getEndAt()).isEqualTo(Instant.parse("2026-12-03T18:00:00Z"));
        assertThat(row.getStartDate()).isNull();
        assertThat(row.getTimeZone()).isEqualTo("America/Los_Angeles");
        assertThat(row.getOrganizerEmail()).isEqualTo("dad@example.com");
        assertThat(row.isOrganizerSelf()).isTrue();
        assertThat(row.getGoogleUpdatedAt()).isEqualTo(Instant.parse("2026-11-21T18:00:00Z"));
        assertThat(row.getMemberIds()).containsExactly(MAYA);
        assertThat(row.getRecurrence()).isNull();
    }

    @Test
    void anAllDayEventKeepsGooglesExclusiveEndDate() throws IOException {
        Event event = parse("""
                {"id":"trip","summary":"Trip","start":{"date":"2026-12-01"},"end":{"date":"2026-12-04"}}""");

        GoogleEvent row = mapper.toCache(event, hub, new UUID[0], new GoogleEvent());

        assertThat(row.isAllDay()).isTrue();
        assertThat(row.getStartDate()).isEqualTo(LocalDate.parse("2026-12-01"));
        assertThat(row.getEndDate()).isEqualTo(LocalDate.parse("2026-12-04"));
        assertThat(row.getStartAt()).isNull();
        assertThat(row.getTimeZone()).isNull();
        // A missing status is Google's default.
        assertThat(row.getStatus()).isEqualTo(GoogleEventStatus.CONFIRMED);
    }

    @Test
    void aSeriesKeepsItsRuleLinesVerbatimAndAnInstanceItsMasterAndOriginalStart() throws IOException {
        Event master = parse("""
                {"id":"weekly","summary":"Class",
                 "start":{"dateTime":"2026-11-06T16:00:00-08:00","timeZone":"America/Los_Angeles"},
                 "end":{"dateTime":"2026-11-06T17:00:00-08:00","timeZone":"America/Los_Angeles"},
                 "recurrence":["RRULE:FREQ=WEEKLY;BYDAY=FR","EXDATE;TZID=America/Los_Angeles:20261127T160000"]}""");
        Event moved = parse("""
                {"id":"weekly_20261114T000000Z","recurringEventId":"weekly","summary":"Class (moved)",
                 "originalStartTime":{"dateTime":"2026-11-13T16:00:00-08:00","timeZone":"America/Los_Angeles"},
                 "start":{"dateTime":"2026-11-14T10:00:00-08:00"},"end":{"dateTime":"2026-11-14T11:00:00-08:00"}}""");

        GoogleEvent masterRow = mapper.toCache(master, hub, new UUID[0], new GoogleEvent());
        GoogleEvent movedRow = mapper.toCache(moved, hub, new UUID[0], new GoogleEvent());

        assertThat(masterRow.getRecurrence()).containsExactly("RRULE:FREQ=WEEKLY;BYDAY=FR",
                "EXDATE;TZID=America/Los_Angeles:20261127T160000");
        assertThat(masterRow.getRecurringEventId()).isNull();
        assertThat(movedRow.getRecurringEventId()).isEqualTo("weekly");
        assertThat(movedRow.getOriginalStartAt()).isEqualTo(Instant.parse("2026-11-14T00:00:00Z"));
        assertThat(movedRow.getStartAt()).isEqualTo(Instant.parse("2026-11-14T18:00:00Z"));
    }

    @Test
    void aCancelledOccurrenceNeedsOnlyItsIdMasterAndOriginalStart() throws IOException {
        Event timed = parse("""
                {"id":"weekly_20261121T000000Z","status":"cancelled","recurringEventId":"weekly",
                 "originalStartTime":{"dateTime":"2026-11-20T16:00:00-08:00"}}""");
        Event allDay = parse("""
                {"id":"daily_20261121","status":"cancelled","recurringEventId":"daily",
                 "originalStartTime":{"date":"2026-11-21"}}""");
        Event deleted = parse("{\"id\":\"gone\",\"status\":\"cancelled\"}");

        GoogleEvent timedRow = mapper.toCache(timed, hub, new UUID[0], new GoogleEvent());
        GoogleEvent allDayRow = mapper.toCache(allDay, hub, new UUID[0], new GoogleEvent());
        GoogleEvent deletedRow = mapper.toCache(deleted, hub, new UUID[0], new GoogleEvent());

        assertThat(timedRow.getStatus()).isEqualTo(GoogleEventStatus.CANCELLED);
        assertThat(timedRow.getOriginalStartAt()).isEqualTo(Instant.parse("2026-11-21T00:00:00Z"));
        assertThat(timedRow.getStartAt()).isNull();
        assertThat(allDayRow.getOriginalStartDate()).isEqualTo(LocalDate.parse("2026-11-21"));
        assertThat(allDayRow.isAllDay()).isTrue();
        assertThat(deletedRow.getStatus()).isEqualTo(GoogleEventStatus.CANCELLED);
        assertThat(deletedRow.getRaw()).contains("\"gone\"");
    }

    @Test
    void theRawEventKeepsWhatTheHubDoesNotModelAndAnUpsertKeepsTheRowId() throws IOException {
        Event event = parse("""
                {"id":"abc","summary":"Swim","start":{"date":"2026-12-03"},"end":{"date":"2026-12-04"},
                 "reminders":{"useDefault":false,"overrides":[{"method":"popup","minutes":30}]},
                 "colorId":"5","conferenceData":{"conferenceId":"xyz"}}""");
        GoogleEvent existing = new GoogleEvent();
        UUID id = UUID.randomUUID();
        existing.setId(id);
        existing.setTitle("Old title");

        GoogleEvent row = mapper.toCache(event, hub, new UUID[0], existing);

        assertThat(row).isSameAs(existing);
        assertThat(row.getId()).isEqualTo(id);
        assertThat(row.getTitle()).isEqualTo("Swim");
        Event back = parse(row.getRaw());
        assertThat(back.getReminders().getOverrides()).singleElement()
                .satisfies(o -> assertThat(o.getMinutes()).isEqualTo(30));
        assertThat(back.getColorId()).isEqualTo("5");
        assertThat(back.getConferenceData().getConferenceId()).isEqualTo("xyz");
    }
}
