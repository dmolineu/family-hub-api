package com.familyhub.demo.google;

import com.familyhub.demo.dto.calendar.CalendarWriteRequest;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;

/**
 * Builds the Google event the hub writes (Phase 4). A change is applied on top of
 * Google's own copy of the event ({@code raw}), so reminders, colours, attachments,
 * conference links and other apps' properties survive the update. The members go in the
 * shared extended property {@value MemberAttribution#MEMBERS_PROPERTY}.
 */
@Component
public class GoogleEventWriter {

    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    /**
     * A new event from a request.
     *
     * @param request      what to create
     * @param fallbackZone the zone for a timed event when the request names none
     * @return the event to insert
     */
    public Event newEvent(CalendarWriteRequest request, String fallbackZone) {
        return applyTo(new Event(), request, fallbackZone);
    }

    /**
     * Google's copy of an event with a request's changes applied.
     *
     * @param raw          Google's event (its JSON from the cache, or a moved event)
     * @param request      the event's new state
     * @param fallbackZone the zone for a timed event when neither the request nor the event names one
     * @return the event to send back
     */
    public Event applyTo(Event raw, CalendarWriteRequest request, String fallbackZone) {
        Event event = copy(raw);
        event.setSummary(request.title());
        event.setDescription(blankToNull(request.description()));
        event.setLocation(blankToNull(request.location()));
        if (request.allDay()) {
            event.setStart(new EventDateTime().setDate(new DateTime(request.startDate().toString())));
            event.setEnd(new EventDateTime().setDate(new DateTime(request.endDate().toString())));
        } else {
            String zone = request.timeZone() != null ? request.timeZone()
                    : event.getStart() != null && event.getStart().getTimeZone() != null
                    ? event.getStart().getTimeZone() : fallbackZone;
            event.setStart(new EventDateTime().setDateTime(new DateTime(Date.from(request.start()), UTC))
                    .setTimeZone(zone));
            event.setEnd(new EventDateTime().setDateTime(new DateTime(Date.from(request.end()), UTC))
                    .setTimeZone(zone));
        }
        if (request.recurrence() != null) {
            String zone = event.getStart() != null && event.getStart().getTimeZone() != null
                    ? event.getStart().getTimeZone() : fallbackZone;
            event.setRecurrence(request.recurrence().isEmpty() ? null
                    : List.copyOf(RecurrenceLines.normalize(request.recurrence(), !request.allDay(), zoneOf(zone))));
        }
        Event.ExtendedProperties properties = event.getExtendedProperties() != null
                ? event.getExtendedProperties() : new Event.ExtendedProperties();
        Map<String, String> shared = properties.getShared() != null
                ? new HashMap<>(properties.getShared()) : new HashMap<>();
        shared.put(MemberAttribution.MEMBERS_PROPERTY, MemberAttribution.tag(request.memberIds()));
        properties.setShared(shared);
        event.setExtendedProperties(properties);
        return event;
    }

    /**
     * Reads Google's event back from the JSON the cache keeps.
     *
     * @param json the cached {@code raw}
     * @return the event
     */
    public Event parse(String json) {
        try {
            return GsonFactory.getDefaultInstance().fromString(json, Event.class);
        } catch (IOException e) {
            throw new UncheckedIOException("A cached Google event is not valid JSON", e);
        }
    }

    // Through JSON: Event.clone() cannot copy the immutable lists and maps callers build.
    private Event copy(Event event) {
        try {
            GsonFactory json = GsonFactory.getDefaultInstance();
            return json.fromString(json.toString(event), Event.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static java.time.ZoneId zoneOf(String zone) {
        try {
            return java.time.ZoneId.of(zone);
        } catch (RuntimeException e) {
            return java.time.ZoneOffset.UTC;
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
