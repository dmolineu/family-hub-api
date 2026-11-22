package com.familyhub.demo.google;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Splitting a series at one occurrence for "this and following" (Phase 4): the old series
 * ends just before it (UNTIL one second earlier for a timed series, one day earlier for an
 * all-day one), and a new series starts there with the rest. A COUNT is shared out between
 * the two, an UNTIL stays with the new series, and EXDATE and RDATE lines go to whichever
 * half their dates fall in.
 */
class SeriesSplitterTest {

    private static final ZoneId LA = ZoneId.of("America/Los_Angeles");

    private final SeriesSplitter splitter = new SeriesSplitter();

    @Test
    void aTimedSeriesEndsOneSecondBeforeTheSplitAndTheNewOneKeepsTheRule() {
        SeriesSplitter.Split split = splitter.splitTimed(new String[]{"RRULE:FREQ=WEEKLY;BYDAY=FR"},
                Instant.parse("2026-11-07T00:00:00Z"), Instant.parse("2026-11-21T00:00:00Z"), LA);

        assertThat(split.before()).containsExactly("RRULE:FREQ=WEEKLY;BYDAY=FR;UNTIL=20261120T235959Z");
        assertThat(split.after()).containsExactly("RRULE:FREQ=WEEKLY;BYDAY=FR");
    }

    @Test
    void anAllDaySeriesEndsTheDayBefore() {
        SeriesSplitter.Split split = splitter.splitAllDay(new String[]{"RRULE:FREQ=DAILY"},
                LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-05"));

        assertThat(split.before()).containsExactly("RRULE:FREQ=DAILY;UNTIL=20261204");
        assertThat(split.after()).containsExactly("RRULE:FREQ=DAILY");
    }

    @Test
    void aCountIsSharedOutAndAnUntilStaysWithTheNewSeries() {
        SeriesSplitter.Split counted = splitter.splitTimed(new String[]{"RRULE:FREQ=WEEKLY;COUNT=10"},
                Instant.parse("2026-11-07T00:00:00Z"), Instant.parse("2026-11-28T00:00:00Z"), LA);
        // Nov 7, 14 and 21 stay; the new series has the other seven.
        assertThat(counted.before()).containsExactly("RRULE:FREQ=WEEKLY;UNTIL=20261127T235959Z");
        assertThat(counted.after()).containsExactly("RRULE:FREQ=WEEKLY;COUNT=7");

        SeriesSplitter.Split until = splitter.splitTimed(
                new String[]{"RRULE:FREQ=WEEKLY;UNTIL=20261231T235959Z;BYDAY=FR"},
                Instant.parse("2026-11-07T00:00:00Z"), Instant.parse("2026-11-21T00:00:00Z"), LA);
        assertThat(until.before()).containsExactly("RRULE:FREQ=WEEKLY;BYDAY=FR;UNTIL=20261120T235959Z");
        assertThat(until.after()).containsExactly("RRULE:FREQ=WEEKLY;UNTIL=20261231T235959Z;BYDAY=FR");
    }

    @Test
    void excludedAndAddedDatesGoToTheHalfTheyFallIn() {
        SeriesSplitter.Split split = splitter.splitTimed(new String[]{
                "RRULE:FREQ=WEEKLY;BYDAY=FR",
                "EXDATE;TZID=America/Los_Angeles:20261113T160000,20261204T160000",
                "EXDATE:20261219T000000Z",
                "RDATE;TZID=America/Los_Angeles:20261110T160000"},
                Instant.parse("2026-11-07T00:00:00Z"), Instant.parse("2026-11-21T00:00:00Z"), LA);

        assertThat(split.before()).containsExactly(
                "RRULE:FREQ=WEEKLY;BYDAY=FR;UNTIL=20261120T235959Z",
                "EXDATE;TZID=America/Los_Angeles:20261113T160000",
                "RDATE;TZID=America/Los_Angeles:20261110T160000");
        assertThat(split.after()).containsExactly(
                "RRULE:FREQ=WEEKLY;BYDAY=FR",
                "EXDATE;TZID=America/Los_Angeles:20261204T160000",
                "EXDATE:20261219T000000Z");
    }

    @Test
    void splittingAtTheFirstDateLeavesNothingBefore() {
        SeriesSplitter.Split split = splitter.splitTimed(new String[]{"RRULE:FREQ=WEEKLY"},
                Instant.parse("2026-11-07T00:00:00Z"), Instant.parse("2026-11-07T00:00:00Z"), LA);

        assertThat(split.startsAtFirst()).isTrue();
        assertThat(split.after()).containsExactly("RRULE:FREQ=WEEKLY");
    }
}
