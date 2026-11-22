package com.familyhub.demo.google;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Google (like RFC 5545) counts a series' start as its first occurrence even when the rule
 * does not match that day, so "every Saturday" started on a Sunday shows on that Sunday
 * too. The hub's old calendar did not, and Google's own screens move the start to the
 * first matching day. The hub does the same before it writes a series.
 */
class RecurrenceStartTest {

    private static final LocalDate SUNDAY = LocalDate.of(2026, 11, 22);

    @Test
    void aStartTheRuleDoesNotMatchMovesToTheRulesFirstDay() {
        assertThat(RecurrenceStart.firstMatchingDay(SUNDAY, List.of("RRULE:FREQ=WEEKLY;BYDAY=SA")))
                .contains(LocalDate.of(2026, 11, 28));
        assertThat(RecurrenceStart.firstMatchingDay(SUNDAY, List.of("RRULE:FREQ=WEEKLY;BYDAY=TU,TH",
                "EXDATE;TZID=America/Los_Angeles:20261201T090000")))
                .contains(LocalDate.of(2026, 11, 24));
        assertThat(RecurrenceStart.firstMatchingDay(SUNDAY, List.of("RRULE:FREQ=MONTHLY;BYMONTHDAY=15")))
                .contains(LocalDate.of(2026, 12, 15));
        // A date-only or a UTC UNTIL still reads.
        assertThat(RecurrenceStart.firstMatchingDay(SUNDAY, List.of("RRULE:FREQ=WEEKLY;UNTIL=20261231;BYDAY=SA")))
                .contains(LocalDate.of(2026, 11, 28));
        assertThat(RecurrenceStart.firstMatchingDay(SUNDAY,
                List.of("RRULE:FREQ=WEEKLY;UNTIL=20270101T075959Z;BYDAY=SA")))
                .contains(LocalDate.of(2026, 11, 28));
    }

    @Test
    void aStartTheRuleMatchesOrCannotBeMovedStaysWhereItIs() {
        assertThat(RecurrenceStart.firstMatchingDay(SUNDAY, List.of("RRULE:FREQ=WEEKLY;BYDAY=SU"))).isEmpty();
        assertThat(RecurrenceStart.firstMatchingDay(SUNDAY, List.of("RRULE:FREQ=DAILY"))).isEmpty();
        assertThat(RecurrenceStart.firstMatchingDay(SUNDAY, List.of("RRULE:FREQ=WEEKLY"))).isEmpty();
        // No rule, extra dates of its own, a rule it cannot read, or one that never matches.
        assertThat(RecurrenceStart.firstMatchingDay(SUNDAY, null)).isEmpty();
        assertThat(RecurrenceStart.firstMatchingDay(SUNDAY, List.of())).isEmpty();
        assertThat(RecurrenceStart.firstMatchingDay(SUNDAY, List.of("RRULE:FREQ=WEEKLY;BYDAY=SA",
                "RDATE;VALUE=DATE:20261123"))).isEmpty();
        assertThat(RecurrenceStart.firstMatchingDay(SUNDAY, List.of("RRULE:FREQ=NOPE"))).isEmpty();
        assertThat(RecurrenceStart.firstMatchingDay(SUNDAY, List.of("RRULE:FREQ=WEEKLY;UNTIL=20261125;BYDAY=SA")))
                .isEmpty();
    }
}
