package com.familyhub.demo.sports;

import java.time.Duration;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * Where team lists, schedules and live scores come from. Each implementation covers the
 * leagues it names; the app never depends on one provider's field names, so a source
 * can be replaced (or one added for a league that has none) without touching the rest.
 */
public interface SportsDataProvider {

    /** The leagues this provider covers. */
    Set<SportsLeague> leagues();

    /** Every team in a league, for the family to search and follow. */
    List<ProviderTeam> teams(SportsLeague league);

    /** One team's games between two dates, inclusive. */
    List<ProviderGame> schedule(SportsLeague league, String teamId, LocalDate from, LocalDate to);

    /**
     * The current state of particular games, for refreshing live scores. The games come
     * as last known, so a source that finds a game by its teams and kickoff, rather than
     * by this provider's id, can still find it. Games it cannot find are left out.
     */
    List<ProviderGame> games(SportsLeague league, Collection<ProviderGame> known);

    /** The live panel's details for one game; empty when the provider has none. */
    Optional<GameSummary> summary(SportsLeague league, String gameId);

    /**
     * Whether {@link #games} can report scores while a game is on. A league without them
     * keeps its games on the calendar (final scores arrive with the schedule) but stays
     * out of the live chip and is never polled.
     */
    default boolean liveScores(SportsLeague league) {
        return true;
    }

    /** How often live games may be refreshed; a source with a small daily quota asks for less often. */
    default Duration liveRefreshInterval(SportsLeague league) {
        return Duration.ZERO;
    }
}
