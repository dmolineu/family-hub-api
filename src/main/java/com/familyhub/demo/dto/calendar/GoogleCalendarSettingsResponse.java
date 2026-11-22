package com.familyhub.demo.dto.calendar;

import java.util.List;
import java.util.UUID;

/**
 * A family's Google calendars and where its new events go (Phase 4).
 *
 * @param calendars         every calendar any signed-in account can see
 * @param defaultCalendarId where new events go, or null when none is chosen
 */
public record GoogleCalendarSettingsResponse(List<GoogleCalendarSettingResponse> calendars, UUID defaultCalendarId) {
}
