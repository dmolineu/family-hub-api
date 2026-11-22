package com.familyhub.demo.dto.calendar;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.UUID;

/**
 * Changes to a family's Google calendars (Phase 4). Calendars not listed keep their
 * settings.
 *
 * @param calendars         the calendars to change
 * @param defaultCalendarId where new events go; null leaves it unchanged
 */
public record UpdateGoogleCalendarSettingsRequest(@Valid List<CalendarSetting> calendars, UUID defaultCalendarId) {

    /**
     * One calendar's settings.
     *
     * @param id            the cached calendar's id
     * @param enabled       whether the calendar shows it
     * @param laneMemberIds whose lanes its unmarked events go in
     */
    public record CalendarSetting(@NotNull UUID id, boolean enabled, List<UUID> laneMemberIds) {
    }
}
