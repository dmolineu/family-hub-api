package com.familyhub.demo.sports;

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

    /** The current state of particular games, for refreshing live scores. */
    List<ProviderGame> games(SportsLeague league, Collection<String> gameIds);

    /** The live panel's details for one game; empty when the provider has none. */
    Optional<GameSummary> summary(SportsLeague league, String gameId);
}
