package com.familyhub.demo.model;

/** What the family inbox did with a message. */
public enum InboxOutcome {
    /** Its photos joined the photo library. */
    PHOTOS,
    /** Events were read from it (added, or waiting for review). */
    EVENTS,
    /** A recipe was read from it into the library. */
    RECIPE,
    /** A reply asking to undo what an earlier message added. */
    UNDO,
    /** From an address the family has not allowed, or not verifiably from it; not read. */
    UNRECOGNISED,
    /** Read, but nothing usable was found, or it needs a reader the server lacks. */
    UNPROCESSED,
    /** Handling it failed; left for a person to look at. */
    FAILED
}
