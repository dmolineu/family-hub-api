package com.familyhub.demo.dto.calendar;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * An event to create, or the new state of one to change (Phase 4). A timed event gives
 * {@code start}/{@code end}; an all-day event gives {@code startDate}/{@code endDate},
 * where the end is the day after the last day, as Google stores it.
 *
 * @param calendarId   the calendar to put it on; null for the family's default (on create)
 *                     or where it is (on change); another calendar on change moves it
 * @param memberIds    whose event it is (one or more lanes)
 * @param title        the title
 * @param description  the description, or null
 * @param location     the location, or null
 * @param allDay       whether it is an all-day event
 * @param start        a timed event's start
 * @param end          a timed event's end
 * @param startDate    an all-day event's first day
 * @param endDate      an all-day event's day after its last day
 * @param timeZone     the zone a timed event and its series are in; null for the calendar's
 * @param recurrence   RRULE/EXDATE/RDATE lines for a series, or null; on change, null keeps them
 * @param notifyGuests whether Google emails the event's guests
 * @param etag         on change, the etag the event was read with; a mismatch is a conflict
 */
public record CalendarWriteRequest(
        UUID calendarId,
        @NotEmpty List<UUID> memberIds,
        @NotBlank @Size(max = 1024) String title,
        @Size(max = 8192) String description,
        @Size(max = 1024) String location,
        boolean allDay,
        Instant start,
        Instant end,
        LocalDate startDate,
        LocalDate endDate,
        @Size(max = 100) String timeZone,
        List<String> recurrence,
        boolean notifyGuests,
        String etag
) {

    /**
     * The same request with an etag.
     *
     * @param newEtag the etag to send
     * @return a copy carrying it
     */
    public CalendarWriteRequest withEtag(String newEtag) {
        return new CalendarWriteRequest(calendarId, memberIds, title, description, location, allDay, start, end,
                startDate, endDate, timeZone, recurrence, notifyGuests, newEtag);
    }

    /**
     * The same request with other recurrence lines.
     *
     * @param newRecurrence the lines, or null to keep an event's own
     * @return a copy carrying them
     */
    public CalendarWriteRequest withRecurrence(List<String> newRecurrence) {
        return new CalendarWriteRequest(calendarId, memberIds, title, description, location, allDay, start, end,
                startDate, endDate, timeZone, newRecurrence, notifyGuests, etag);
    }

    /**
     * The same request for another calendar.
     *
     * @param newCalendarId the calendar
     * @return a copy naming it
     */
    public CalendarWriteRequest onCalendar(UUID newCalendarId) {
        return new CalendarWriteRequest(newCalendarId, memberIds, title, description, location, allDay, start, end,
                startDate, endDate, timeZone, recurrence, notifyGuests, etag);
    }
}
