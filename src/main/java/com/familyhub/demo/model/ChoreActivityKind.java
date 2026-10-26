package com.familyhub.demo.model;

/** What happened to a chore, as recorded in the append-only chore log. */
public enum ChoreActivityKind {
    /** Ticked off; carries the points only when no approval was needed. */
    COMPLETED,
    /** A parent approved a kid's chore; carries the points. */
    APPROVED,
    /** A parent rejected a kid's chore, which was unticked. */
    REJECTED,
    /** Unticked before any points were granted. */
    UNCOMPLETED,
    /** Unticked after points were granted; carries the points taken back (negative). */
    REVERSED
}
