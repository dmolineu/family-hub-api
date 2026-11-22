package com.familyhub.demo.google;

import com.familyhub.demo.model.GoogleCalendar;
import com.familyhub.demo.model.GoogleEvent;
import com.familyhub.demo.model.GoogleEventStatus;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Turns a Google event into its cache row (Phase 4), as Google sent it: timed events as
 * instants plus the event's own zone, all-day events as dates with Google's exclusive
 * end, rule lines verbatim, and the whole event as JSON in {@code raw}. Writes onto a
 * given row so an upsert keeps the row's id, which the inbox and the app point at.
 */
@Component
public class GoogleEventCacheMapper {

    /**
     * Copies a Google event onto a cache row.
     *
     * @param event     the event as Google returned it, possibly a sparse cancelled one
     * @param calendar  the cached calendar it came from
     * @param memberIds whose lanes it goes in (from {@link MemberAttribution})
     * @param target    the row to fill: a new one, or the cached row being updated
     * @return {@code target}, filled
     */
    public GoogleEvent toCache(Event event, GoogleCalendar calendar, UUID[] memberIds, GoogleEvent target) {
        EventDateTime start = event.getStart();
        EventDateTime end = event.getEnd();
        EventDateTime original = event.getOriginalStartTime();
        target.setCalendar(calendar);
        target.setGoogleEventId(event.getId());
        target.setIcalUid(event.getICalUID());
        target.setEtag(event.getEtag());
        target.setStatus(GoogleEventStatus.fromGoogle(event.getStatus()));
        target.setTitle(event.getSummary());
        target.setDescription(event.getDescription());
        target.setLocation(event.getLocation());
        target.setHtmlLink(event.getHtmlLink());
        // A cancelled occurrence has no start; its original start still says which kind it was.
        target.setAllDay(start != null ? start.getDate() != null : original != null && original.getDate() != null);
        target.setStartAt(instant(start));
        target.setEndAt(instant(end));
        target.setStartDate(date(start));
        target.setEndDate(date(end));
        target.setTimeZone(start == null ? null : start.getTimeZone());
        target.setRecurrence(event.getRecurrence() == null ? null : event.getRecurrence().toArray(String[]::new));
        target.setRecurringEventId(event.getRecurringEventId());
        target.setOriginalStartAt(instant(original));
        target.setOriginalStartDate(date(original));
        target.setOrganizerEmail(event.getOrganizer() == null ? null : event.getOrganizer().getEmail());
        target.setOrganizerSelf(event.getOrganizer() != null && Boolean.TRUE.equals(event.getOrganizer().getSelf()));
        target.setGoogleUpdatedAt(event.getUpdated() == null ? null : Instant.ofEpochMilli(event.getUpdated().getValue()));
        target.setMemberIds(memberIds);
        target.setRaw(json(event));
        return target;
    }

    private static Instant instant(EventDateTime value) {
        return value == null || value.getDateTime() == null ? null : Instant.ofEpochMilli(value.getDateTime().getValue());
    }

    private static LocalDate date(EventDateTime value) {
        DateTime date = value == null ? null : value.getDate();
        return date == null ? null : LocalDate.parse(date.toStringRfc3339());
    }

    private static String json(Event event) {
        try {
            return GsonFactory.getDefaultInstance().toString(event);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write Google event " + event.getId() + " as JSON", e);
        }
    }
}
