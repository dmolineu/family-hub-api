package com.familyhub.demo.service;

import com.familyhub.demo.google.MemberAttribution;
import com.familyhub.demo.model.CalendarEvent;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;

/**
 * The Google event one of the hub's own pre-Phase 4 events becomes (Phase 4). Its times
 * are the family's wall-clock times in the family's zone; an all-day end becomes Google's
 * exclusive day; a series keeps its RRULE, with its excluded and cancelled dates as one
 * EXDATE line. The event names its member and the row it came from in shared properties.
 */
@Component
public class LegacyEventConverter {

    /** The shared extended property naming the hub row an event was copied from. */
    public static final String LEGACY_ID_PROPERTY = "familyhubLegacyId";

    private static final DateTimeFormatter COMPACT_LOCAL = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss");
    private static final DateTimeFormatter COMPACT_DATE = DateTimeFormatter.BASIC_ISO_DATE;
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    /**
     * The Google event for a single event or a series' master.
     *
     * @param row        the hub's row
     * @param exceptions the series' changed and cancelled dates (empty for a single event)
     * @param zone       the family's zone
     * @return the event to insert
     */
    public Event toGoogle(CalendarEvent row, List<CalendarEvent> exceptions, ZoneId zone) {
        Event event = new Event()
                .setSummary(row.getTitle())
                .setDescription(row.getDescription())
                .setLocation(row.getLocation());
        // A series starts on its first matching day: the old calendar never showed it on a
        // start day its rule skips, and Google would (see RecurrenceStart).
        LocalDate day = row.getRecurrenceRule() == null ? row.getDate()
                : com.familyhub.demo.google.RecurrenceStart.firstMatchingDay(row.getDate(),
                List.of("RRULE:" + row.getRecurrenceRule())).orElse(row.getDate());
        times(event, row, day, zone);
        if (row.getRecurrenceRule() != null) {
            List<String> lines = new ArrayList<>();
            lines.add("RRULE:" + (row.isAllDay() ? row.getRecurrenceRule()
                    : com.familyhub.demo.google.RecurrenceLines.untilInUtc(row.getRecurrenceRule(), zone)));
            Set<LocalDate> excluded = new LinkedHashSet<>();
            if (row.getExdates() != null && !row.getExdates().isBlank()) {
                Arrays.stream(row.getExdates().split(",")).map(String::trim).map(LocalDate::parse).forEach(excluded::add);
            }
            exceptions.stream().filter(CalendarEvent::isCancelled).map(CalendarEvent::getOriginalDate)
                    .forEach(excluded::add);
            if (!excluded.isEmpty()) {
                lines.add(row.isAllDay()
                        ? "EXDATE;VALUE=DATE:" + String.join(",", excluded.stream().map(COMPACT_DATE::format).toList())
                        : "EXDATE;TZID=" + zone.getId() + ":" + String.join(",", excluded.stream()
                        .map(date -> COMPACT_LOCAL.format(date.atTime(row.getStartTime()))).toList()));
            }
            event.setRecurrence(lines);
        }
        Map<String, String> shared = new HashMap<>();
        shared.put(MemberAttribution.MEMBERS_PROPERTY, row.getMember().getId().toString());
        shared.put(LEGACY_ID_PROPERTY, row.getId().toString());
        event.setExtendedProperties(new Event.ExtendedProperties().setShared(shared));
        return event;
    }

    /**
     * Where a changed date of a series originally was, as Google names the occurrence.
     *
     * @param master    the series' master row
     * @param exception the changed date's row
     * @param zone      the family's zone
     * @return the occurrence's original start
     */
    public EventDateTime originalStart(CalendarEvent master, CalendarEvent exception, ZoneId zone) {
        if (master.isAllDay()) {
            return new EventDateTime().setDate(new DateTime(exception.getOriginalDate().toString()));
        }
        ZonedDateTime start = exception.getOriginalDate().atTime(master.getStartTime()).atZone(zone);
        return new EventDateTime().setDateTime(new DateTime(Date.from(start.toInstant()), UTC))
                .setTimeZone(zone.getId());
    }

    /**
     * Google's occurrence with a changed date's own fields.
     *
     * @param instance  the occurrence as Google returned it
     * @param exception the changed date's row
     * @param zone      the family's zone
     * @return the occurrence to send back
     */
    public Event applyException(Event instance, CalendarEvent exception, ZoneId zone) {
        instance.setSummary(exception.getTitle());
        instance.setDescription(exception.getDescription());
        instance.setLocation(exception.getLocation());
        times(instance, exception, exception.getDate(), zone);
        Map<String, String> shared = new HashMap<>();
        if (instance.getExtendedProperties() != null && instance.getExtendedProperties().getShared() != null) {
            shared.putAll(instance.getExtendedProperties().getShared());
        }
        shared.put(MemberAttribution.MEMBERS_PROPERTY, exception.getMember().getId().toString());
        shared.put(LEGACY_ID_PROPERTY, exception.getId().toString());
        instance.setExtendedProperties(new Event.ExtendedProperties().setShared(shared));
        return instance;
    }

    // The row's times on {@code day}: its own date, or a series' first matching day.
    private static void times(Event event, CalendarEvent row, LocalDate day, ZoneId zone) {
        if (row.isAllDay()) {
            LocalDate last = row.getEndDate() != null ? row.getEndDate() : row.getDate();
            LocalDate movedLast = last.plusDays(ChronoUnit.DAYS.between(row.getDate(), day));
            event.setStart(new EventDateTime().setDate(new DateTime(day.toString())));
            event.setEnd(new EventDateTime().setDate(new DateTime(movedLast.plusDays(1).toString())));
            return;
        }
        LocalDateTime start = day.atTime(row.getStartTime());
        LocalDateTime end = day.atTime(row.getEndTime());
        if (end.isBefore(start)) {
            // 23:00 to 01:00 ends the next day.
            end = end.plusDays(1);
        }
        event.setStart(new EventDateTime().setDateTime(new DateTime(Date.from(start.atZone(zone).toInstant()), UTC))
                .setTimeZone(zone.getId()));
        event.setEnd(new EventDateTime().setDateTime(new DateTime(Date.from(end.atZone(zone).toInstant()), UTC))
                .setTimeZone(zone.getId()));
    }
}
