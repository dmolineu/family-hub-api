package com.familyhub.demo.dto;

import jakarta.validation.constraints.NotBlank;

/** A reward on offer, in the family backup. Archived rewards and asks are left out. */
public record RewardBackup(@NotBlank String title, String description, int points) {
}
