package com.familyhub.demo.google;

import com.familyhub.demo.dto.calendar.CalendarWriteRequest;
import com.google.api.services.calendar.model.Event;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A series the rule does not start on is written from its first matching day, keeping its
 * time and length, so Google does not show it on the day it was made as well. A change that
 * leaves the series' lines alone (one date of it) is written where it is asked to be.
 */
class GoogleEventWriterSeriesStartTest {

    private static final String LA = "America/Los_Angeles";
    private final GoogleEventWriter writer = new GoogleEventWriter();

    private static CalendarWriteRequest timed(Instant start, Instant end, List<String> recurrence) {
        return new CalendarWriteRequest(null, List.of(UUID.randomUUID()), "Saturday Soccer", null, null, false,
                start, end, null, null, LA, recurrence, false, null);
    }

    private static Instant startOf(Event event) {
        return Instant.ofEpochMilli(event.getStart().getDateTime().getValue());
    }

    private static Instant endOf(Event event) {
        return Instant.ofEpochMilli(event.getEnd().getDateTime().getValue());
    }

    @Test
    void aTimedSeriesMovesToItsFirstMatchingDayAtTheSameLocalTime() {
        // Sunday Nov 22, 9-10 AM Pacific; the first Saturday is Nov 28.
        Event event = writer.newEvent(timed(Instant.parse("2026-11-22T17:00:00Z"), Instant.parse("2026-11-22T18:00:00Z"),
                List.of("RRULE:FREQ=WEEKLY;BYDAY=SA")), LA);

        assertThat(startOf(event)).isEqualTo(Instant.parse("2026-11-28T17:00:00Z"));
        assertThat(endOf(event)).isEqualTo(Instant.parse("2026-11-28T18:00:00Z"));
        assertThat(event.getRecurrence()).containsExactly("RRULE:FREQ=WEEKLY;BYDAY=SA");
    }

    @Test
    void theDayIsTheFamilysNotUtcs() {
        // Friday Nov 27, 9 PM Pacific is already Saturday in UTC; Friday is not a Saturday.
        Event event = writer.newEvent(timed(Instant.parse("2026-11-28T05:00:00Z"), Instant.parse("2026-11-28T06:00:00Z"),
                List.of("RRULE:FREQ=WEEKLY;BYDAY=SA")), LA);

        assertThat(startOf(event)).isEqualTo(Instant.parse("2026-11-29T05:00:00Z"));
    }

    @Test
    void anAllDaySeriesMovesWithItsLength() {
        Event event = writer.newEvent(new CalendarWriteRequest(null, List.of(UUID.randomUUID()), "Market", null, null,
                true, null, null, LocalDate.of(2026, 11, 22), LocalDate.of(2026, 11, 23), null,
                List.of("RRULE:FREQ=WEEKLY;BYDAY=SA"), false, null), LA);

        assertThat(event.getStart().getDate().toStringRfc3339()).isEqualTo("2026-11-28");
        assertThat(event.getEnd().getDate().toStringRfc3339()).isEqualTo("2026-11-29");
    }

    @Test
    void aMatchingStartASingleEventOrUnchangedLinesStayWhereAsked() {
        Instant sunday = Instant.parse("2026-11-22T17:00:00Z");
        Instant sundayEnd = Instant.parse("2026-11-22T18:00:00Z");

        assertThat(startOf(writer.newEvent(timed(sunday, sundayEnd, List.of("RRULE:FREQ=WEEKLY;BYDAY=SU")), LA)))
                .isEqualTo(sunday);
        assertThat(startOf(writer.newEvent(timed(sunday, sundayEnd, null), LA))).isEqualTo(sunday);
        assertThat(startOf(writer.applyTo(new Event().setRecurrence(List.of("RRULE:FREQ=WEEKLY;BYDAY=SA")),
                timed(sunday, sundayEnd, null), LA))).isEqualTo(sunday);
    }
}
