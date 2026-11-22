package com.familyhub.demo.dto.calendar;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Whether the family's Google calendars are up to date, for the calendar's banner
 * (Phase 4).
 *
 * @param connected         whether any member has signed in to Google
 * @param needsReauth       whether Google refused an account, which must sign in again
 * @param writeScopeMissing whether an account signed in before the hub could write, so
 *                          its calendars cannot be edited until it signs in again
 * @param oldestSyncAt      the least recent sync of an enabled calendar, or null
 * @param calendars         each enabled calendar's last sync
 */
public record GoogleHealthResponse(boolean connected, boolean needsReauth, boolean writeScopeMissing,
                                   Instant oldestSyncAt, List<CalendarHealth> calendars) {

    /**
     * One calendar's last sync.
     *
     * @param id            the cached calendar's id
     * @param summary       its name
     * @param lastSyncedAt  when it was last synced
     * @param lastSyncError why the last sync failed, if it did
     */
    public record CalendarHealth(UUID id, String summary, Instant lastSyncedAt, String lastSyncError) {
    }
}
