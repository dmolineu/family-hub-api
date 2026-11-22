package com.familyhub.demo.model;

import java.util.Locale;

/** A Google event's {@code status}. A cancelled instance hides one occurrence of a series. */
public enum GoogleEventStatus {
    CONFIRMED,
    TENTATIVE,
    CANCELLED;

    /**
     * Reads Google's lower-case spelling.
     *
     * @param value the status Google returned, possibly null
     * @return the matching status; null or unknown values count as {@link #CONFIRMED},
     *         Google's own default
     */
    public static GoogleEventStatus fromGoogle(String value) {
        if (value == null) {
            return CONFIRMED;
        }
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "cancelled" -> CANCELLED;
            case "tentative" -> TENTATIVE;
            default -> CONFIRMED;
        };
    }
}
