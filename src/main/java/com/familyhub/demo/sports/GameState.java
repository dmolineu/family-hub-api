package com.familyhub.demo.sports;

/** Where a game stands, the same words for every league and provider. */
public enum GameState {
    SCHEDULED,
    LIVE,
    FINAL,
    POSTPONED,
    CANCELLED,
    SUSPENDED;

    /** Whether nothing more will happen to the score today. */
    public boolean isSettled() {
        return this != SCHEDULED && this != LIVE;
    }
}
