package com.familyhub.demo.google;

import com.familyhub.demo.model.GoogleEvent;
import com.familyhub.demo.model.GoogleEventStatus;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Expanding a cached series into the occurrences a window shows (Phase 4), in
 * America/Los_Angeles. A timed series keeps its local start time and its local length
 * across DST changes, so the instants move instead. Every EXDATE form Google writes is
 * honoured, RDATEs add dates, and each occurrence a changed or cancelled instance stands
 * for is replaced by it, wherever that instance was moved to.
 */
class GoogleRecurrenceExpanderTest {

    private static final ZoneId LA = ZoneId.of("America/Los_Angeles");

    private final GoogleRecurrenceExpander expander = new GoogleRecurrenceExpander();

    private static GoogleEvent master(String start, String end, String... lines) {
        GoogleEvent event = new GoogleEvent();
        event.setId(UUID.randomUUID());
        event.setGoogleEventId("series");
        event.setTitle("Series");
        event.setStartAt(Instant.parse(start));
        event.setEndAt(Instant.parse(end));
        event.setTimeZone("America/Los_Angeles");
        event.setRecurrence(lines);
        return event;
    }

    private static GoogleEvent allDayMaster(String start, String exclusiveEnd, String... lines) {
        GoogleEvent event = new GoogleEvent();
        event.setId(UUID.randomUUID());
        event.setGoogleEventId("series");
        event.setAllDay(true);
        event.setStartDate(LocalDate.parse(start));
        event.setEndDate(LocalDate.parse(exclusiveEnd));
        event.setRecurrence(lines);
        return event;
    }

    private static GoogleEvent instance(String originalStart, GoogleEventStatus status, String start, String end) {
        GoogleEvent event = new GoogleEvent();
        event.setId(UUID.randomUUID());
        event.setGoogleEventId("series_" + originalStart);
        event.setRecurringEventId("series");
        event.setStatus(status);
        event.setOriginalStartAt(Instant.parse(originalStart));
        if (start != null) {
            event.setTitle("Moved");
            event.setStartAt(Instant.parse(start));
            event.setEndAt(Instant.parse(end));
        }
        return event;
    }

    private static GoogleRecurrenceExpander.Window window(String first, String lastInclusive) {
        return GoogleRecurrenceExpander.Window.of(LocalDate.parse(first), LocalDate.parse(lastInclusive), LA);
    }

    private List<Instant> starts(GoogleEvent master, List<GoogleEvent> instances, String first, String last) {
        return expander.expand(master, instances, LA, window(first, last)).stream()
                .map(GoogleRecurrenceExpander.Occurrence::start).toList();
    }

    private List<LocalDate> dates(GoogleEvent master, String first, String last) {
        return expander.expand(master, List.of(), LA, window(first, last)).stream()
                .map(GoogleRecurrenceExpander.Occurrence::startDate).toList();
    }

    @Test
    void aWeeklyNineOClockStaysAtNineAndOneHourAcrossBothDstChanges() {
        GoogleEvent spring = master("2026-03-01T17:00:00Z", "2026-03-01T18:00:00Z", "RRULE:FREQ=WEEKLY;COUNT=3");
        List<GoogleRecurrenceExpander.Occurrence> march =
                expander.expand(spring, List.of(), LA, window("2026-03-01", "2026-03-31"));
        assertThat(march).extracting(GoogleRecurrenceExpander.Occurrence::start).containsExactly(
                Instant.parse("2026-03-01T17:00:00Z"), Instant.parse("2026-03-08T16:00:00Z"),
                Instant.parse("2026-03-15T16:00:00Z"));
        assertThat(march).allSatisfy(o -> assertThat(Duration.between(o.start(), o.end())).hasMinutes(60));

        GoogleEvent fall = master("2026-10-25T16:00:00Z", "2026-10-25T17:00:00Z", "RRULE:FREQ=WEEKLY;COUNT=2");
        assertThat(starts(fall, List.of(), "2026-10-01", "2026-11-30")).containsExactly(
                Instant.parse("2026-10-25T16:00:00Z"), Instant.parse("2026-11-01T17:00:00Z"));
    }

    @Test
    void untilInUtcAndCountEndTheSeries() {
        GoogleEvent until = master("2026-12-01T17:00:00Z", "2026-12-01T18:00:00Z",
                "RRULE:FREQ=DAILY;UNTIL=20261203T170000Z");
        assertThat(starts(until, List.of(), "2026-11-01", "2026-12-31")).hasSize(3);

        GoogleEvent count = master("2026-12-01T17:00:00Z", "2026-12-01T18:00:00Z", "RRULE:FREQ=DAILY;COUNT=5");
        assertThat(starts(count, List.of(), "2026-11-01", "2026-12-31")).hasSize(5);
    }

    @Test
    void aDateOnlyUntilOnATimedSeriesEndsAtTheEndOfThatDayInTheSeriesZone() {
        // The app writes "repeat until" as a date; on a timed series it means through that day.
        GoogleEvent daily = master("2026-11-20T17:00:00Z", "2026-11-20T18:00:00Z",
                "RRULE:FREQ=DAILY;UNTIL=20261123");

        assertThat(starts(daily, List.of(), "2026-11-01", "2026-11-30")).containsExactly(
                Instant.parse("2026-11-20T17:00:00Z"), Instant.parse("2026-11-21T17:00:00Z"),
                Instant.parse("2026-11-22T17:00:00Z"), Instant.parse("2026-11-23T17:00:00Z"));
    }

    @Test
    void yearlyOnTheTwentyNinthOfFebruaryOnlyFallsInLeapYears() {
        GoogleEvent leap = allDayMaster("2028-02-29", "2028-03-01", "RRULE:FREQ=YEARLY");
        assertThat(dates(leap, "2028-01-01", "2036-12-31")).containsExactly(
                LocalDate.parse("2028-02-29"), LocalDate.parse("2032-02-29"), LocalDate.parse("2036-02-29"));
    }

    @Test
    void monthlyOrdinalsAndSetPositionsFollowTheRule() {
        GoogleEvent secondTuesday = allDayMaster("2026-12-08", "2026-12-09", "RRULE:FREQ=MONTHLY;BYDAY=2TU;COUNT=3");
        assertThat(dates(secondTuesday, "2026-12-01", "2027-03-31")).containsExactly(
                LocalDate.parse("2026-12-08"), LocalDate.parse("2027-01-12"), LocalDate.parse("2027-02-09"));

        GoogleEvent lastWeekday = allDayMaster("2026-11-30", "2026-12-01",
                "RRULE:FREQ=MONTHLY;BYDAY=MO,TU,WE,TH,FR;BYSETPOS=-1;COUNT=2");
        assertThat(dates(lastWeekday, "2026-11-01", "2027-01-31")).containsExactly(
                LocalDate.parse("2026-11-30"), LocalDate.parse("2026-12-31"));
    }

    @Test
    void everyExdateFormGoogleWritesRemovesItsOccurrence() {
        // Fridays at 16:00 Pacific (00:00 UTC on Saturday).
        GoogleEvent fridays = master("2026-11-07T00:00:00Z", "2026-11-07T01:00:00Z",
                "RRULE:FREQ=WEEKLY;BYDAY=FR",
                // Another zone: 19:00 in New York is 16:00 Pacific.
                "EXDATE;TZID=America/New_York:20261113T190000",
                "EXDATE:20261121T000000Z",
                "EXDATE;TZID=America/Los_Angeles:20261204T160000,20261211T160000");

        assertThat(starts(fridays, List.of(), "2026-11-01", "2026-12-31")).containsExactly(
                Instant.parse("2026-11-07T00:00:00Z"), Instant.parse("2026-11-28T00:00:00Z"),
                Instant.parse("2026-12-19T00:00:00Z"), Instant.parse("2026-12-26T00:00:00Z"));

        GoogleEvent days = allDayMaster("2026-12-01", "2026-12-02", "RRULE:FREQ=DAILY;COUNT=4",
                "EXDATE;VALUE=DATE:20261202,20261203");
        assertThat(dates(days, "2026-12-01", "2026-12-31")).containsExactly(
                LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-04"));
    }

    @Test
    void anRdateAddsAnOccurrenceAndAPeriodIsSkipped() {
        GoogleEvent series = master("2026-12-01T17:00:00Z", "2026-12-01T18:00:00Z", "RRULE:FREQ=DAILY;COUNT=2",
                "RDATE;TZID=America/Los_Angeles:20261210T090000",
                "RDATE;VALUE=PERIOD:20261215T170000Z/PT1H");

        assertThat(starts(series, List.of(), "2026-12-01", "2026-12-31")).containsExactly(
                Instant.parse("2026-12-01T17:00:00Z"), Instant.parse("2026-12-02T17:00:00Z"),
                Instant.parse("2026-12-10T17:00:00Z"));
    }

    @Test
    void changedAndCancelledInstancesReplaceTheirOccurrenceWhereverTheyMoved() {
        GoogleEvent fridays = master("2026-11-07T00:00:00Z", "2026-11-07T01:00:00Z", "RRULE:FREQ=WEEKLY;BYDAY=FR");
        GoogleEvent cancelled = instance("2026-11-14T00:00:00Z", GoogleEventStatus.CANCELLED, null, null);
        GoogleEvent moved = instance("2026-11-21T00:00:00Z", GoogleEventStatus.CONFIRMED,
                "2026-11-22T18:00:00Z", "2026-11-22T19:00:00Z");
        // Its own date is in the window; its original one, in January, is not.
        GoogleEvent movedIn = instance("2027-01-02T00:00:00Z", GoogleEventStatus.CONFIRMED,
                "2026-11-30T18:00:00Z", "2026-11-30T19:00:00Z");
        // Its original date is in the window; it was moved out of it.
        GoogleEvent movedOut = instance("2026-11-28T00:00:00Z", GoogleEventStatus.CONFIRMED,
                "2027-02-01T18:00:00Z", "2027-02-01T19:00:00Z");

        List<GoogleRecurrenceExpander.Occurrence> november = expander.expand(fridays,
                List.of(cancelled, moved, movedIn, movedOut), LA, window("2026-11-01", "2026-11-30"));

        assertThat(november).extracting(GoogleRecurrenceExpander.Occurrence::start).containsExactly(
                Instant.parse("2026-11-07T00:00:00Z"), Instant.parse("2026-11-22T18:00:00Z"),
                Instant.parse("2026-11-30T18:00:00Z"));
        assertThat(november.get(0).modified()).isFalse();
        assertThat(november.get(0).source()).isSameAs(fridays);
        assertThat(november.get(0).originalStart()).isEqualTo("20261107T000000Z");
        assertThat(november.get(1).modified()).isTrue();
        assertThat(november.get(1).source()).isSameAs(moved);
        assertThat(november.get(1).originalStart()).isEqualTo("20261121T000000Z");
    }

    @Test
    void aRecurringAllDayEventKeepsItsLengthAndOverlapsTheWindowItReaches() {
        GoogleEvent weekend = allDayMaster("2026-12-04", "2026-12-07", "RRULE:FREQ=WEEKLY;COUNT=2");

        List<GoogleRecurrenceExpander.Occurrence> found =
                expander.expand(weekend, List.of(), LA, window("2026-12-06", "2026-12-31"));

        assertThat(found).extracting(GoogleRecurrenceExpander.Occurrence::startDate)
                .containsExactly(LocalDate.parse("2026-12-04"), LocalDate.parse("2026-12-11"));
        assertThat(found).extracting(GoogleRecurrenceExpander.Occurrence::endDate)
                .containsExactly(LocalDate.parse("2026-12-07"), LocalDate.parse("2026-12-14"));
        assertThat(found.getFirst().originalStart()).isEqualTo("20261204");
    }

    @Test
    void aTimedOccurrenceThatStartedBeforeTheWindowButEndsInItIsShown() {
        GoogleEvent nights = master("2026-12-01T07:00:00Z", "2026-12-01T10:00:00Z", "RRULE:FREQ=DAILY;COUNT=3");

        // 23:00-02:00 Pacific from Nov 30. Dec 2 shows the night that began on Dec 1 (it ends
        // at 02:00 on Dec 2) and the one that begins on Dec 2; Nov 30's ended on Dec 1.
        assertThat(starts(nights, List.of(), "2026-12-02", "2026-12-02"))
                .containsExactly(Instant.parse("2026-12-02T07:00:00Z"), Instant.parse("2026-12-03T07:00:00Z"));
    }

    @Test
    void aSeriesThatEndedBeforeTheWindowShowsNothingAndOneSeriesStopsAtAThousand() {
        GoogleEvent ended = master("2025-01-01T17:00:00Z", "2025-01-01T18:00:00Z",
                "RRULE:FREQ=DAILY;UNTIL=20250131T170000Z");
        assertThat(starts(ended, List.of(), "2026-12-01", "2026-12-31")).isEmpty();

        GoogleEvent hourly = master("2026-11-01T08:00:00Z", "2026-11-01T08:30:00Z", "RRULE:FREQ=HOURLY");
        assertThat(starts(hourly, List.of(), "2026-11-01", "2026-12-31")).hasSize(1000);
    }
}
