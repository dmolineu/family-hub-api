package com.familyhub.demo.model;

/** What happened, as recorded in the append-only points log: chores and, since the shop, rewards. */
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
    REVERSED,
    /** A parent approved a reward the member asked for; carries the points spent (negative). */
    REDEEMED,
    /** A parent said no to a reward the member asked for; no points change. */
    DECLINED
}
