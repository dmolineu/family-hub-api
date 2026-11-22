package com.familyhub.demo.dto.calendar;

import java.util.List;
import java.util.UUID;

/**
 * One event, or one occurrence of a series, as the calendar shows it (Phase 4). Timed
 * events have ISO instants in {@code start}/{@code end}; all-day events have
 * {@code yyyy-MM-dd} dates with Google's exclusive end. An occurrence of a series is
 * named by its series ({@code recurringEventId}) and its {@code originalStart} in
 * Google's compact form; {@code occurrenceId} is unique within one response.
 *
 * @param id               the cache row: the event, the series' master, or the changed instance
 * @param occurrenceId     unique per occurrence: the id, or {@code <series id>:<original start>}
 * @param calendarId       the cached calendar it is on
 * @param calendarColor    that calendar's colour
 * @param googleEventId    Google's id of the row in {@code id}
 * @param etag             that row's etag, sent back with a change
 * @param recurringEventId the series' master row, for an occurrence; null for a single event
 * @param originalStart    the occurrence's original start ({@code 20260308T170000Z} or
 *                         {@code 20260308}); null for a single event
 * @param recurring        whether it is an occurrence of a series
 * @param recurrence       the series' RRULE/EXDATE/RDATE lines; null for a single event
 * @param title            the title
 * @param description      the description
 * @param location         the location
 * @param htmlLink         the event in Google Calendar
 * @param allDay           whether it is an all-day event
 * @param start            an ISO instant, or a date for an all-day event
 * @param end              an ISO instant, or the day after the last day for an all-day event
 * @param timeZone         the event's zone (else its calendar's, else the family's)
 * @param memberIds        whose lanes it goes in
 * @param status           {@code confirmed} or {@code tentative}
 * @param canEdit          whether a signed-in account may change it
 */
public record CalendarEventResponse(
        UUID id,
        String occurrenceId,
        UUID calendarId,
        String calendarColor,
        String googleEventId,
        String etag,
        UUID recurringEventId,
        String originalStart,
        boolean recurring,
        List<String> recurrence,
        String title,
        String description,
        String location,
        String htmlLink,
        boolean allDay,
        String start,
        String end,
        String timeZone,
        List<UUID> memberIds,
        String status,
        boolean canEdit
) {
}
