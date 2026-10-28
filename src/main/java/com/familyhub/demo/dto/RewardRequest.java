package com.familyhub.demo.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/**
 * A parent adding or changing a reward in the shop, with their own PIN, because kids
 * at the wall could otherwise price their own treats.
 */
public record RewardRequest(
        @NotNull(message = "Choose which parent this is")
        UUID approverMemberId,

        @NotBlank(message = "Enter your PIN")
        String pin,

        @NotBlank(message = "Give the reward a name")
        @Size(max = 60, message = "Keep the name to 60 characters")
        String title,

        @Size(max = 200, message = "Keep the description to 200 characters")
        String description,

        @Min(value = 1, message = "A reward costs 1 to 10,000 points")
        @Max(value = 10_000, message = "A reward costs 1 to 10,000 points")
        int points
) {
}
