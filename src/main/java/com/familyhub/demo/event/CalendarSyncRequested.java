package com.familyhub.demo.event;

import java.util.UUID;

/**
 * A calendar should be synced soon (Phase 4): the hub just wrote to it, or a write found
 * it out of date. Handled after the commit, off the request thread.
 *
 * @param calendarId the cached calendar's id
 */
public record CalendarSyncRequested(UUID calendarId) {
}
