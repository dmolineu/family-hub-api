package com.familyhub.demo.sports;

import java.time.Instant;
import java.util.List;

/**
 * A game as a provider reports it.
 *
 * @param timeTbd    the start time is a placeholder (college kick-offs and some MLB games); show "time TBA"
 * @param broadcasts TV channels, deduplicated, at most a few
 * @param detail     what to show beside the score: "Top 5th", "Final/10", "Postponed: Rain"; null before a game
 */
public record ProviderGame(
        String id,
        SportsLeague league,
        Instant startAt,
        boolean timeTbd,
        GameSide home,
        GameSide away,
        String venue,
        List<String> broadcasts,
        GameState state,
        String detail
) {
}
