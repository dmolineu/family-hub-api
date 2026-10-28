package com.familyhub.demo.dto;

import com.familyhub.demo.sports.SportsLeague;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/** Follow a team found in a league's team search. */
public record FollowTeamRequest(
        @NotNull(message = "Choose a league") SportsLeague league,
        @NotBlank(message = "Choose a team") String providerTeamId
) {
}
