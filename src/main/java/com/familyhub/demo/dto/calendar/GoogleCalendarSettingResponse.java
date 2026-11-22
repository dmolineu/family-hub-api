package com.familyhub.demo.dto.calendar;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * One Google calendar in Family Settings (Phase 4).
 *
 * @param id               the cached calendar's id
 * @param googleCalendarId Google's calendar id
 * @param summary          its name
 * @param color            its colour
 * @param timeZone         its time zone
 * @param enabled          whether the calendar shows it
 * @param accessRole       the best role any signed-in account has on it ({@code owner},
 *                         {@code writer}, {@code reader}, {@code freeBusyReader}); null if none
 * @param writable         whether the hub can add and change events on it
 * @param laneMemberIds    whose lanes its events go in when they name no members
 * @param isDefault        whether new events go on it
 * @param lastSyncedAt     when it was last synced
 * @param lastSyncError    why the last sync failed, if it did
 */
public record GoogleCalendarSettingResponse(
        UUID id,
        String googleCalendarId,
        String summary,
        String color,
        String timeZone,
        boolean enabled,
        String accessRole,
        boolean writable,
        List<UUID> laneMemberIds,
        boolean isDefault,
        Instant lastSyncedAt,
        String lastSyncError
) {
}
