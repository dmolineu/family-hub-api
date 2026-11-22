package com.familyhub.demo.dto;

/**
 * Rows restored per module, for the confirmation shown to the family. Since Phase 4 no
 * events are restored ({@code events} is 0): they live in Google Calendar, and an older
 * file's events are left out and counted in {@code eventsSkipped}.
 */
public record RestoreSummary(int members, int chores, int lists, int recipes, int meals, int events, int themePacks,
                             int avatarPacks, int rewards, int followedTeams, int eventsSkipped) {

    /** The shape before Phase 4 left events out. */
    public RestoreSummary(int members, int chores, int lists, int recipes, int meals, int events, int themePacks,
                          int avatarPacks, int rewards, int followedTeams) {
        this(members, chores, lists, recipes, meals, events, themePacks, avatarPacks, rewards, followedTeams, 0);
    }

    /** The shape before followed teams existed. */
    public RestoreSummary(int members, int chores, int lists, int recipes, int meals, int events, int themePacks,
                          int avatarPacks, int rewards) {
        this(members, chores, lists, recipes, meals, events, themePacks, avatarPacks, rewards, 0);
    }

    /** The shape before the rewards shop existed. */
    public RestoreSummary(int members, int chores, int lists, int recipes, int meals, int events, int themePacks,
                          int avatarPacks) {
        this(members, chores, lists, recipes, meals, events, themePacks, avatarPacks, 0, 0);
    }

    /** The shape before avatar packs existed. */
    public RestoreSummary(int members, int chores, int lists, int recipes, int meals, int events, int themePacks) {
        this(members, chores, lists, recipes, meals, events, themePacks, 0, 0, 0);
    }
}
