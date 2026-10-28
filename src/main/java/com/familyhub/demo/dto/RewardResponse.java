package com.familyhub.demo.dto;

import com.familyhub.demo.model.Reward;

import java.util.UUID;

/** A reward on offer. */
public record RewardResponse(UUID id, String title, String description, int points) {

    /** Maps a catalogue entry. */
    public static RewardResponse toDto(Reward reward) {
        return new RewardResponse(reward.getId(), reward.getTitle(), reward.getDescription(), reward.getPoints());
    }
}
