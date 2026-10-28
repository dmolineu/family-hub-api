package com.familyhub.demo.dto;

import com.familyhub.demo.sports.GameState;
import com.familyhub.demo.sports.SportsLeague;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A followed team's game for the calendar and the live chip. Read-only: games are never
 * written to Google Calendar.
 *
 * @param color      the first followed team's colour in this game; null for neutral
 * @param memberIds  fans of the followed teams in this game, whose lanes show it
 * @param hideScores some followed team in this game hides its scores; devices show
 *                   "Score hidden" until someone taps to reveal it there
 */
public record SportsGameResponse(
        UUID id,
        String providerGameId,
        SportsLeague league,
        Instant startAt,
        boolean timeTbd,
        Side home,
        Side away,
        String venue,
        List<String> broadcasts,
        GameState state,
        String detail,
        List<UUID> followedTeamIds,
        String color,
        List<UUID> memberIds,
        boolean hideScores
) {

    /** One side of the game; the logo only for a followed team. */
    public record Side(String teamId, String name, String abbreviation, Integer score, String logoUrl) {
    }
}
