package com.familyhub.demo.google;

import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A date-only UNTIL on a timed series (what the app's "repeat until" writes) is made the
 * end of that day in the series' zone, in UTC, which RFC 5545 requires of a timed series
 * and which ical4j and Google can both read. All-day series and other lines are left as
 * they are.
 */
class RecurrenceLinesTest {

    private static final ZoneId LA = ZoneId.of("America/Los_Angeles");

    @Test
    void aDateOnlyUntilOnATimedSeriesBecomesTheEndOfThatDayInUtc() {
        assertThat(RecurrenceLines.normalize(List.of("RRULE:FREQ=DAILY;UNTIL=20261123",
                "EXDATE;TZID=America/Los_Angeles:20261121T090000"), true, LA))
                .containsExactly("RRULE:FREQ=DAILY;UNTIL=20261124T075959Z",
                        "EXDATE;TZID=America/Los_Angeles:20261121T090000");
        // In summer the offset differs: the end of July 4 Pacific is 06:59:59 UTC on July 5.
        assertThat(RecurrenceLines.normalize(List.of("RRULE:FREQ=WEEKLY;UNTIL=20270704;BYDAY=SU"), true, LA))
                .containsExactly("RRULE:FREQ=WEEKLY;UNTIL=20270705T065959Z;BYDAY=SU");
    }

    @Test
    void anAllDaySeriesOrAUtcUntilIsLeftAsItIs() {
        assertThat(RecurrenceLines.normalize(List.of("RRULE:FREQ=DAILY;UNTIL=20261123"), false, LA))
                .containsExactly("RRULE:FREQ=DAILY;UNTIL=20261123");
        assertThat(RecurrenceLines.normalize(List.of("RRULE:FREQ=DAILY;UNTIL=20261124T075959Z"), true, LA))
                .containsExactly("RRULE:FREQ=DAILY;UNTIL=20261124T075959Z");
        assertThat(RecurrenceLines.normalize(null, true, LA)).isNull();
    }
}
