package com.familyhub.demo.google;

import com.familyhub.demo.model.GoogleOAuthToken;
import com.google.api.services.calendar.model.CalendarListEntry;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;

import java.time.Instant;
import java.util.List;

/**
 * The only thing that calls Google Calendar (Phase 4). Every method acts as one
 * signed-in account and throws {@link GoogleCalendarException} on any failure. The real
 * implementation talks to Google; tests and the dev profile use an in-memory fake.
 * Events are Google's own model, because the cache keeps Google's JSON verbatim.
 */
public interface GoogleCalendarGateway {

    /**
     * One events.list request. Recurring events always come back as masters plus their
     * edited or cancelled instances ({@code singleEvents=false}), never expanded.
     *
     * @param syncToken              the token from the last sync, for an incremental list
     * @param pageToken              the next page of the same list, or null for the first
     * @param timeMin                where a full sync starts, or null; never with a sync token
     * @param sharedExtendedProperty a {@code key=value} filter on shared extended
     *                               properties, or null
     */
    record ListRequest(String syncToken, String pageToken, Instant timeMin, String sharedExtendedProperty) {

        /**
         * A page of a full sync.
         *
         * @param timeMin   where the sync starts
         * @param pageToken the next page, or null for the first
         * @return the request
         */
        public static ListRequest fullSync(Instant timeMin, String pageToken) {
            return new ListRequest(null, pageToken, timeMin, null);
        }

        /**
         * A page of an incremental sync.
         *
         * @param syncToken the token the last sync ended with
         * @param pageToken the next page, or null for the first
         * @return the request
         */
        public static ListRequest incremental(String syncToken, String pageToken) {
            return new ListRequest(syncToken, pageToken, null, null);
        }

        /**
         * Events carrying one shared extended property, e.g. an earlier copy of a hub event.
         *
         * @param key   the property's name
         * @param value its value
         * @return the request
         */
        public static ListRequest withSharedProperty(String key, String value) {
            return new ListRequest(null, null, null, key + "=" + value);
        }
    }

    /**
     * One page of events.list.
     *
     * @param items         the events on this page, deleted ones included when syncing
     * @param nextPageToken the next page, or null on the last page
     * @param nextSyncToken the token for the next incremental sync; only on the last page
     */
    record EventPage(List<Event> items, String nextPageToken, String nextSyncToken) {
    }

    /**
     * Lists one page of a calendar's events.
     *
     * @param account    the account to act as
     * @param calendarId Google's calendar id
     * @param request    what to list
     * @return the page
     */
    EventPage listEvents(GoogleOAuthToken account, String calendarId, ListRequest request);

    /**
     * Reads one event.
     *
     * @param account    the account to act as
     * @param calendarId Google's calendar id
     * @param eventId    Google's event id
     * @return the event as Google holds it now
     */
    Event getEvent(GoogleOAuthToken account, String calendarId, String eventId);

    /**
     * Creates an event.
     *
     * @param account      the account to act as
     * @param calendarId   Google's calendar id
     * @param event        the event to create
     * @param notifyGuests whether Google emails the attendees
     * @return the created event, with its id and etag
     */
    Event insertEvent(GoogleOAuthToken account, String calendarId, Event event, boolean notifyGuests);

    /**
     * Replaces an event, only if it has not changed since {@code etag} was read.
     *
     * @param account      the account to act as
     * @param calendarId   Google's calendar id
     * @param eventId      Google's event id (a master, a single, or one instance)
     * @param event        the whole event to store
     * @param etag         the etag it was read with; a mismatch is a CONFLICT
     * @param notifyGuests whether Google emails the attendees
     * @return the updated event
     */
    Event updateEvent(GoogleOAuthToken account, String calendarId, String eventId, Event event, String etag,
                      boolean notifyGuests);

    /**
     * Deletes an event, only if it has not changed since {@code etag} was read.
     *
     * @param account    the account to act as
     * @param calendarId Google's calendar id
     * @param eventId    Google's event id
     * @param etag       the etag it was read with; a mismatch is a CONFLICT
     */
    void deleteEvent(GoogleOAuthToken account, String calendarId, String eventId, String etag);

    /**
     * Finds one occurrence of a recurring event, as an instance that can be updated or
     * deleted on its own.
     *
     * @param account       the account to act as
     * @param calendarId    Google's calendar id
     * @param masterEventId the recurring master's Google id
     * @param originalStart the occurrence's original start
     * @return the instance
     */
    Event getInstance(GoogleOAuthToken account, String calendarId, String masterEventId,
                      EventDateTime originalStart);

    /**
     * Moves a master or single event to another calendar (Google cannot move one instance).
     *
     * @param account               the account to act as
     * @param calendarId            the calendar the event is on
     * @param eventId               Google's event id
     * @param destinationCalendarId the calendar to move it to
     * @return the moved event
     */
    Event moveEvent(GoogleOAuthToken account, String calendarId, String eventId, String destinationCalendarId);

    /**
     * The calendars the account can see, with its access role on each.
     *
     * @param account the account to act as
     * @return every calendar list entry, all pages
     */
    List<CalendarListEntry> listCalendars(GoogleOAuthToken account);
}
