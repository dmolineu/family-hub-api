package com.familyhub.demo.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Changes to a followed team; a null field is left as it is.
 *
 * @param memberIds the team's fans (its games show in their lanes); an empty list clears them
 * @param color     #rrggbb, or an empty string to go back to the neutral colour
 */
public record FollowedTeamUpdateRequest(
        @Size(max = 20, message = "Choose at most 20 fans") List<UUID> memberIds,
        @Pattern(regexp = "^(#[0-9a-fA-F]{6})?$", message = "A colour is #rrggbb") String color,
        Boolean showOnCalendar,
        Boolean hideScores
) {
}
