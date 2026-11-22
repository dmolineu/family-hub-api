package com.familyhub.demo.service;

import com.familyhub.demo.model.CalendarEvent;
import com.familyhub.demo.model.FamilyMember;
import com.google.api.services.calendar.model.Event;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The hub's old calendar never showed a series on a start day its rule skips. Google
 * would, so the copy starts the series on its first matching day instead.
 */
class LegacyEventConverterSeriesStartTest {

    private static final ZoneId LA = ZoneId.of("America/Los_Angeles");
    private final LegacyEventConverter converter = new LegacyEventConverter();

    private static CalendarEvent row(String rule, boolean allDay) {
        FamilyMember member = new FamilyMember();
        member.setId(UUID.randomUUID());
        CalendarEvent row = new CalendarEvent();
        row.setId(UUID.randomUUID());
        row.setTitle("Class");
        row.setDate(LocalDate.of(2026, 11, 22));
        row.setStartTime(LocalTime.of(16, 0));
        row.setEndTime(LocalTime.of(17, 0));
        row.setAllDay(allDay);
        row.setRecurrenceRule(rule);
        row.setMember(member);
        return row;
    }

    @Test
    void aSeriesStartsOnItsFirstMatchingDay() {
        Event timed = converter.toGoogle(row("FREQ=WEEKLY;BYDAY=FR", false), List.of(), LA);
        assertThat(Instant.ofEpochMilli(timed.getStart().getDateTime().getValue()))
                .isEqualTo(Instant.parse("2026-11-28T00:00:00Z"));
        assertThat(Instant.ofEpochMilli(timed.getEnd().getDateTime().getValue()))
                .isEqualTo(Instant.parse("2026-11-28T01:00:00Z"));

        Event allDay = converter.toGoogle(row("FREQ=WEEKLY;BYDAY=FR", true), List.of(), LA);
        assertThat(allDay.getStart().getDate().toStringRfc3339()).isEqualTo("2026-11-27");
        assertThat(allDay.getEnd().getDate().toStringRfc3339()).isEqualTo("2026-11-28");
    }

    @Test
    void aMatchingSeriesOrASingleEventKeepsItsDay() {
        Event matching = converter.toGoogle(row("FREQ=WEEKLY;BYDAY=SU", false), List.of(), LA);
        assertThat(Instant.ofEpochMilli(matching.getStart().getDateTime().getValue()))
                .isEqualTo(Instant.parse("2026-11-23T00:00:00Z"));

        Event single = converter.toGoogle(row(null, false), List.of(), LA);
        assertThat(Instant.ofEpochMilli(single.getStart().getDateTime().getValue()))
                .isEqualTo(Instant.parse("2026-11-23T00:00:00Z"));
    }
}
