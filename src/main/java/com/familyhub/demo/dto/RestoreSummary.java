package com.familyhub.demo.dto;

/** Rows restored per module, for the confirmation shown to the family. */
public record RestoreSummary(int members, int chores, int lists, int recipes, int meals, int events, int themePacks,
                             int avatarPacks, int rewards) {

    /** The shape before the rewards shop existed. */
    public RestoreSummary(int members, int chores, int lists, int recipes, int meals, int events, int themePacks,
                          int avatarPacks) {
        this(members, chores, lists, recipes, meals, events, themePacks, avatarPacks, 0);
    }

    /** The shape before avatar packs existed. */
    public RestoreSummary(int members, int chores, int lists, int recipes, int meals, int events, int themePacks) {
        this(members, chores, lists, recipes, meals, events, themePacks, 0, 0);
    }
}
