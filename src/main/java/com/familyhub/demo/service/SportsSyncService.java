package com.familyhub.demo.service;

import com.familyhub.demo.config.SportsProperties;
import com.familyhub.demo.model.FollowedTeam;
import com.familyhub.demo.model.SportsGame;
import com.familyhub.demo.repository.FollowedTeamRepository;
import com.familyhub.demo.repository.SportsGameRepository;
import com.familyhub.demo.sports.GameSide;
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
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    /** When each league's source was last asked for live scores, to keep to its pace. */
    private final Map<SportsLeague, Instant> lastLiveRefresh = new ConcurrentHashMap<>();

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
     * A league whose source has no live scores is skipped (its final scores come with
     * the daily schedule), and a source with a daily quota is asked no more often than
     * it allows.
     */
    @Transactional
    public void refreshLive() {
        Instant now = Instant.now(clock);
        List<SportsGame> open = sportsGameRepository.findByStateInAndStartAtGreaterThanEqualAndStartAtLessThan(
                OPEN, now.minus(properties.getLiveWindowAfter()), now.plus(properties.getLiveWindowBefore()));
        // Each game once per league, though several families may follow it.
        Map<SportsLeague, TreeMap<String, ProviderGame>> knownByLeague = open.stream().collect(Collectors.groupingBy(
                SportsGame::getLeague, Collectors.toMap(SportsGame::getProviderGameId, SportsSyncService::known,
                        (first, second) -> first, TreeMap::new)));
        knownByLeague.forEach((league, known) -> {
            Optional<SportsDataProvider> provider = providers.forLeague(league);
            if (provider.isEmpty() || !provider.get().liveScores(league)) {
                return;
            }
            Instant last = lastLiveRefresh.get(league);
            if (last != null && now.isBefore(last.plus(provider.get().liveRefreshInterval(league)))) {
                return;
            }
            lastLiveRefresh.put(league, now);
            try {
                for (ProviderGame game : provider.get().games(league, List.copyOf(known.values()))) {
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

    /** A cached game as its provider would describe it. */
    private static ProviderGame known(SportsGame game) {
        return new ProviderGame(
                game.getProviderGameId(),
                game.getLeague(),
                game.getStartAt(),
                game.isTimeTbd(),
                new GameSide(game.getHomeTeamId(), game.getHomeName(), game.getHomeAbbreviation(), game.getHomeScore()),
                new GameSide(game.getAwayTeamId(), game.getAwayName(), game.getAwayAbbreviation(), game.getAwayScore()),
                game.getVenue(),
                game.getBroadcasts() == null ? List.of() : List.of(game.getBroadcasts().split("\n")),
                game.getState(),
                game.getDetail());
    }

    private void apply(SportsGame cached, ProviderGame game, Instant now) {
        // A live source can report a final score before the schedule source has it
        // (nflverse takes up to a day), so a schedule that has not caught up never
        // reopens a finished game.
        boolean behind = cached.getState() == GameState.FINAL && game.state() == GameState.SCHEDULED
                && game.home().score() == null && game.away().score() == null;
        cached.setStartAt(game.startAt());
        cached.setTimeTbd(game.timeTbd());
        cached.setHomeTeamId(game.home().teamId());
        cached.setHomeName(game.home().name());
        cached.setHomeAbbreviation(game.home().abbreviation());
        cached.setAwayTeamId(game.away().teamId());
        cached.setAwayName(game.away().name());
        cached.setAwayAbbreviation(game.away().abbreviation());
        cached.setVenue(game.venue());
        cached.setBroadcasts(game.broadcasts() == null || game.broadcasts().isEmpty()
                ? null : String.join("\n", game.broadcasts()));
        if (!behind) {
            cached.setHomeScore(game.home().score());
            cached.setAwayScore(game.away().score());
            cached.setState(game.state());
            cached.setDetail(game.detail());
        }
        cached.setUpdatedAt(now);
        sportsGameRepository.save(cached);
    }
}
