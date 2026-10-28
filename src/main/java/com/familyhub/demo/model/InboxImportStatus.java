package com.familyhub.demo.model;

/** Where an import stands. */
public enum InboxImportStatus {
    /** Added to the family's data. */
    ADDED,
    /** Waiting for a parent to review it. */
    PENDING,
    /** A parent turned it down. */
    REJECTED,
    /** Added, then taken off again. */
    UNDONE
}
