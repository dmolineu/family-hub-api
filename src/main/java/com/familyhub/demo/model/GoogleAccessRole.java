package com.familyhub.demo.model;

import java.util.Locale;

/**
 * What a signed-in Google account may do on a calendar: Google's calendarList
 * {@code accessRole}. Only owners and writers can add or change events.
 */
public enum GoogleAccessRole {
    OWNER,
    WRITER,
    READER,
    FREE_BUSY_READER;

    /**
     * Reads Google's spelling ({@code owner}, {@code writer}, {@code reader},
     * {@code freeBusyReader}).
     *
     * @param value the accessRole Google returned
     * @return the matching role; anything unknown counts as {@link #FREE_BUSY_READER},
     *         the least access, so an unexpected value never grants writes
     */
    public static GoogleAccessRole fromGoogle(String value) {
        if (value == null) {
            return FREE_BUSY_READER;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "owner" -> OWNER;
            case "writer" -> WRITER;
            case "reader" -> READER;
            default -> FREE_BUSY_READER;
        };
    }

    /**
     * Whether an account with this role can create, change and delete events.
     *
     * @return true for owners and writers
     */
    public boolean canWrite() {
        return this == OWNER || this == WRITER;
    }
}
