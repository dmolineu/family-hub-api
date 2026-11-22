package com.familyhub.demo.google;

import java.util.Set;

/**
 * The Google OAuth scopes the hub asks for, and whether a granted set lets it write
 * events (Phase 4). Accounts that signed in before the hub could write hold only
 * {@code calendar.events.readonly}; they keep syncing, but nothing is written through
 * them until they sign in again.
 */
public final class GoogleScopes {

    /** See, create, change and delete events. */
    public static final String EVENTS = "https://www.googleapis.com/auth/calendar.events";
    /** See the list of calendars an account has. */
    public static final String CALENDAR_LIST_READONLY = "https://www.googleapis.com/auth/calendar.calendarlist.readonly";
    /** Everything in Google Calendar; also lets the hub write. */
    public static final String CALENDAR = "https://www.googleapis.com/auth/calendar";
    /** What the hub asks for when a member connects Google, space-separated. */
    public static final String REQUESTED = EVENTS + " " + CALENDAR_LIST_READONLY;

    private static final Set<String> WRITE_SCOPES = Set.of(EVENTS, CALENDAR);

    private GoogleScopes() {
    }

    /**
     * Whether a granted set of scopes lets the hub write events.
     *
     * @param granted the space-separated scopes Google granted, possibly null
     * @return true if it includes the events scope or the whole calendar scope
     */
    public static boolean canWrite(String granted) {
        if (granted == null || granted.isBlank()) {
            return false;
        }
        for (String scope : granted.trim().split("\\s+")) {
            if (WRITE_SCOPES.contains(scope)) {
                return true;
            }
        }
        return false;
    }
}
