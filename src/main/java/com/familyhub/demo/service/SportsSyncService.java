package com.familyhub.demo.service;

import com.familyhub.demo.config.SportsProperties;
import com.familyhub.demo.model.FollowedTeam;
import com.familyhub.demo.model.SportsGame;
import com.familyhub.demo.repository.FollowedTeamRepository;
import com.familyhub.demo.repository.SportsGameRepository;
import com.familyhub.demo.sports.GameState;
import com.familyhub.demo.sports.ProviderGame;
import com.familyhub.demo.sports.SportsDataProvider;
import com.familyhub.demo.sports.SportsLeague;
import com.familyhub.demo.sports.SportsProviders;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeSet;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Reads followed teams' schedules into the game cache, and refreshes the scores of
 * games that are live. Every device reads the cache, so a wall and five phones still
 * cost one upstream request per refresh.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SportsSyncService {
    private static final EnumSet<GameState> OPEN = EnumSet.of(GameState.SCHEDULED, GameState.LIVE);

    private final FollowedTeamRepository followedTeamRepository;
    private final SportsGameRepository sportsGameRepository;
    private final SportsProviders providers;
    private final SportsProperties properties;
    private final Clock clock;

    /**
     * Reads one team's schedule. A failure (the source is down, say) is logged and
     * leaves the team unsynced, so the next pass tries again; it never fails a request.
     */
    @Transactional
    public void syncTeamQuietly(UUID teamId) {
        followedTeamRepository.findById(teamId).ifPresent(team -> {
            try {
                syncTeam(team);
            } catch (RuntimeException e) {
                log.warn("Schedule sync failed, team={}, league={}: {}", team.getProviderTeamId(), team.getLeague(),
                        e.toString());
            }
        });
    }

    /** Teams never synced: just followed while the source was down, or restored from a backup. */
    @Transactional
    public void syncUnsynced() {
        followedTeamRepository.findByLastSyncedAtIsNull().forEach(team -> syncTeamQuietly(team.getId()));
    }

    /** Every followed team, for the daily refresh that fills in TBD times and late changes. */
    @Transactional
    public void syncAll() {
        List<FollowedTeam> teams = followedTeamRepository.findAll();
        teams.forEach(team -> syncTeamQuietly(team.getId()));
        log.info("Daily sports sync, teams={}", teams.size());
    }

    /**
     * Refreshes games that are live or about to start, in any family: one provider
     * request per league, and nothing at all when no followed game is in its window.
     */
    @Transactional
    public void refreshLive() {
        Instant now = Instant.now(clock);
        List<SportsGame> open = sportsGameRepository.findByStateInAndStartAtGreaterThanEqualAndStartAtLessThan(
                OPEN, now.minus(properties.getLiveWindowAfter()), now.plus(properties.getLiveWindowBefore()));
        Map<SportsLeague, TreeSet<String>> idsByLeague = open.stream().collect(Collectors.groupingBy(
                SportsGame::getLeague, Collectors.mapping(SportsGame::getProviderGameId,
                        Collectors.toCollection(TreeSet::new))));
        idsByLeague.forEach((league, ids) -> {
            Optional<SportsDataProvider> provider = providers.forLeague(league);
            if (provider.isEmpty()) {
                return;
            }
            try {
                for (ProviderGame game : provider.get().games(league, List.copyOf(ids))) {
                    for (SportsGame cached : sportsGameRepository.findByLeagueAndProviderGameId(league, game.id())) {
                        apply(cached, game, now);
                    }
                }
            } catch (RuntimeException e) {
                log.warn("Live refresh failed, league={}: {}", league, e.toString());
            }
        });
    }

    private void syncTeam(FollowedTeam team) {
        SportsDataProvider provider = providers.forLeague(team.getLeague()).orElseThrow(
                () -> new IllegalStateException("No data source for " + team.getLeague()));
        ZoneId zone = ZoneId.of(FamilyTimezoneResolver.resolveStoredTimezoneOrDefault(team.getFamily().getTimezone()));
        LocalDate today = LocalDate.now(clock.withZone(zone));
        Instant now = Instant.now(clock);
        List<ProviderGame> games = provider.schedule(team.getLeague(), team.getProviderTeamId(),
                today.minusDays(properties.getScheduleDaysBehind()), today.plusDays(properties.getScheduleDaysAhead()));
        for (ProviderGame game : games) {
            SportsGame cached = sportsGameRepository
                    .findByFamilyAndLeagueAndProviderGameId(team.getFamily(), team.getLeague(), game.id())
                    .orElseGet(() -> {
                        SportsGame created = new SportsGame();
                        created.setFamily(team.getFamily());
                        created.setLeague(team.getLeague());
                        created.setProviderGameId(game.id());
                        return created;
                    });
            apply(cached, game, now);
        }
        team.setLastSyncedAt(now);
        followedTeamRepository.save(team);
        log.info("Schedule synced, team={}, league={}, games={}", team.getProviderTeamId(), team.getLeague(),
                games.size());
    }

    private void apply(SportsGame cached, ProviderGame game, Instant now) {
        cached.setStartAt(game.startAt());
        cached.setTimeTbd(game.timeTbd());
        cached.setHomeTeamId(game.home().teamId());
        cached.setHomeName(game.home().name());
        cached.setHomeAbbreviation(game.home().abbreviation());
        cached.setHomeScore(game.home().score());
        cached.setAwayTeamId(game.away().teamId());
        cached.setAwayName(game.away().name());
        cached.setAwayAbbreviation(game.away().abbreviation());
        cached.setAwayScore(game.away().score());
        cached.setVenue(game.venue());
        cached.setBroadcasts(game.broadcasts() == null || game.broadcasts().isEmpty()
                ? null : String.join("\n", game.broadcasts()));
        cached.setState(game.state());
        cached.setDetail(game.detail());
        cached.setUpdatedAt(now);
        sportsGameRepository.save(cached);
    }
}
