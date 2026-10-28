package com.familyhub.demo.sports;

import com.familyhub.demo.config.SportsProperties;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Each league's team list, fetched from its provider at most once a day (they change
 * once a season), so searching as a parent types costs no upstream requests.
 */
@Component
@RequiredArgsConstructor
public class SportsTeamCatalog {
    private final SportsProviders providers;
    private final SportsProperties properties;
    private final Clock clock;
    private final Map<SportsLeague, Cached> cache = new ConcurrentHashMap<>();

    private record Cached(List<ProviderTeam> teams, Instant fetchedAt) {}

    /** Teams whose name, short name or abbreviation contains the query (all for a blank query), by name. */
    public List<ProviderTeam> search(SportsDataProvider provider, SportsLeague league, String query) {
        String needle = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        return teams(provider, league).stream()
                .filter(team -> needle.isEmpty()
                        || contains(team.name(), needle)
                        || contains(team.shortName(), needle)
                        || contains(team.abbreviation(), needle))
                .sorted(Comparator.comparing(ProviderTeam::name))
                .toList();
    }

    /** One team by the provider's id. */
    public Optional<ProviderTeam> find(SportsDataProvider provider, SportsLeague league, String providerTeamId) {
        return teams(provider, league).stream().filter(t -> t.id().equals(providerTeamId)).findFirst();
    }

    private List<ProviderTeam> teams(SportsDataProvider provider, SportsLeague league) {
        Instant now = Instant.now(clock);
        Cached cached = cache.get(league);
        if (cached == null || cached.fetchedAt().plus(properties.getTeamListTtl()).isBefore(now)) {
            cached = new Cached(List.copyOf(provider.teams(league)), now);
            cache.put(league, cached);
        }
        return cached.teams();
    }

    private static boolean contains(String value, String needle) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needle);
    }
}
