package com.familyhub.demo.model;

/**
 * A completion is PENDING while a kid's chore waits for a parent and APPROVED once its
 * points count. A rejected completion is deleted (so the chore can be done again) and
 * only the log remembers it.
 */
public enum ChoreCompletionStatus {
    PENDING,
    APPROVED
}
