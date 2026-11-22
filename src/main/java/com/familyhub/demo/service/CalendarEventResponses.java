package com.familyhub.demo.service;

import com.familyhub.demo.dto.calendar.CalendarEventResponse;
import com.familyhub.demo.google.GoogleRecurrenceExpander.Occurrence;
import com.familyhub.demo.model.GoogleCalendar;
import com.familyhub.demo.model.GoogleEvent;

import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/** Builds {@link CalendarEventResponse}s from cache rows, for reads and for writes (Phase 4). */
final class CalendarEventResponses {

    private static final String NO_TITLE = "(No title)";

    private CalendarEventResponses() {
    }

    /**
     * A single event, or a series' master as a whole (what a write of it returns).
     *
     * @param row        the cache row
     * @param canEdit    whether a signed-in account may change it
     * @param familyZone the family's zone, the last fallback for the event's zone
     * @return the response
     */
    static CalendarEventResponse forRow(GoogleEvent row, boolean canEdit, ZoneId familyZone) {
        boolean series = row.getRecurrence() != null && row.getRecurringEventId() == null;
        return build(row, row.getId().toString(), series ? row.getId() : null, null,
                series ? row.getRecurrence() : null, row.isAllDay(),
                row.isAllDay() ? row.getStartDate().toString() : row.getStartAt().toString(),
                row.isAllDay() ? row.getEndDate().toString() : row.getEndAt().toString(), canEdit, familyZone);
    }

    /**
     * One occurrence of a series.
     *
     * @param master     the series' master row
     * @param occurrence the occurrence
     * @param canEdit    whether a signed-in account may change it
     * @param familyZone the family's zone
     * @return the response
     */
    static CalendarEventResponse forOccurrence(GoogleEvent master, Occurrence occurrence, boolean canEdit,
                                               ZoneId familyZone) {
        boolean allDay = occurrence.startDate() != null;
        return build(occurrence.source(), master.getId() + ":" + occurrence.originalStart(), master.getId(),
                occurrence.originalStart(), master.getRecurrence(), allDay,
                allDay ? occurrence.startDate().toString() : occurrence.start().toString(),
                allDay ? occurrence.endDate().toString() : occurrence.end().toString(), canEdit, familyZone);
    }

    /**
     * A changed occurrence, as a write of it returns it.
     *
     * @param instance      the instance row
     * @param master        the series' master row
     * @param originalStart the occurrence's original start, compact
     * @param canEdit       whether a signed-in account may change it
     * @param familyZone    the family's zone
     * @return the response
     */
    static CalendarEventResponse forInstance(GoogleEvent instance, GoogleEvent master, String originalStart,
                                             boolean canEdit, ZoneId familyZone) {
        return build(instance, master.getId() + ":" + originalStart, master.getId(), originalStart,
                master.getRecurrence(), instance.isAllDay(),
                instance.isAllDay() ? instance.getStartDate().toString() : instance.getStartAt().toString(),
                instance.isAllDay() ? instance.getEndDate().toString() : instance.getEndAt().toString(),
                canEdit, familyZone);
    }

    private static CalendarEventResponse build(GoogleEvent source, String occurrenceId, java.util.UUID seriesId,
                                               String originalStart, String[] recurrence, boolean allDay,
                                               String start, String end, boolean canEdit, ZoneId familyZone) {
        GoogleCalendar calendar = source.getCalendar();
        return new CalendarEventResponse(
                source.getId(),
                occurrenceId,
                calendar.getId(),
                calendar.getColor(),
                source.getGoogleEventId(),
                source.getEtag(),
                seriesId,
                originalStart,
                seriesId != null,
                recurrence == null ? null : List.of(recurrence),
                source.getTitle() == null || source.getTitle().isBlank() ? NO_TITLE : source.getTitle(),
                source.getDescription(),
                source.getLocation(),
                source.getHtmlLink(),
                allDay,
                start,
                end,
                source.getTimeZone() != null ? source.getTimeZone() : zoneOf(calendar, familyZone).getId(),
                source.getMemberIds() == null ? List.of() : Arrays.asList(source.getMemberIds()),
                source.getStatus().name().toLowerCase(Locale.ROOT),
                canEdit);
    }

    /**
     * A calendar's zone, or the family's when it has none or an unknown one.
     *
     * @param calendar   the cached calendar
     * @param familyZone the family's zone
     * @return the zone
     */
    static ZoneId zoneOf(GoogleCalendar calendar, ZoneId familyZone) {
        if (calendar.getTimeZone() == null) {
            return familyZone;
        }
        try {
            return ZoneId.of(calendar.getTimeZone());
        } catch (DateTimeException e) {
            return familyZone;
        }
    }
}
