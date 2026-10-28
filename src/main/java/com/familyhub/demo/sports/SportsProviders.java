package com.familyhub.demo.sports;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;

/** Which provider covers which league. A league with none shows as "not available yet". */
@Component
@RequiredArgsConstructor
public class SportsProviders {
    private final List<SportsDataProvider> providers;

    /** The provider for a league, if any covers it. */
    public Optional<SportsDataProvider> forLeague(SportsLeague league) {
        return providers.stream().filter(p -> p.leagues().contains(league)).findFirst();
    }
}
