package com.familyhub.demo.service;

import com.familyhub.demo.config.SportsProperties;
import com.familyhub.demo.dto.SportsGameResponse;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.FollowedTeam;
import com.familyhub.demo.model.SportsGame;
import com.familyhub.demo.repository.FollowedTeamRepository;
import com.familyhub.demo.repository.SportsGameRepository;
import com.familyhub.demo.sports.GameState;
import com.familyhub.demo.sports.GameSummary;
import com.familyhub.demo.sports.SportsProviders;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** Followed teams' games for the calendar, the live chip and its panel, from the cache. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SportsGameService {
    private final SportsGameRepository sportsGameRepository;
    private final FollowedTeamRepository followedTeamRepository;
    private final SportsProviders providers;
    private final SportsProperties properties;
    private final Clock clock;
    private final Map<String, CachedSummary> summaries = new ConcurrentHashMap<>();

    private record CachedSummary(GameSummary summary, Instant fetchedAt) {}

    /**
     * Games starting on the given days in the family's time zone, of teams shown on the
     * calendar, earliest first.
     */
    public List<SportsGameResponse> games(Family family, LocalDate from, LocalDate to) {
        ZoneId zone = ZoneId.of(FamilyTimezoneResolver.resolveStoredTimezoneOrDefault(family.getTimezone()));
        List<FollowedTeam> teams = followedTeamRepository.findByFamilyOrderByCreatedAtAsc(family);
        return sportsGameRepository.findByFamilyAndStartAtGreaterThanEqualAndStartAtLessThanOrderByStartAtAsc(
                        family, from.atStartOfDay(zone).toInstant(), to.plusDays(1).atStartOfDay(zone).toInstant())
                .stream()
                .filter(game -> followedIn(game, teams).stream().anyMatch(FollowedTeam::isShowOnCalendar))
                .map(game -> toDto(game, teams))
                .toList();
    }

    /** Games live now or about to start, for the live chip; any followed team, shown or not. */
    public List<SportsGameResponse> live(Family family) {
        Instant now = Instant.now(clock);
        List<FollowedTeam> teams = followedTeamRepository.findByFamilyOrderByCreatedAtAsc(family);
        return sportsGameRepository.findByFamilyAndStartAtGreaterThanEqualAndStartAtLessThanOrderByStartAtAsc(
                        family, now.minus(properties.getLiveWindowAfter()), now.plus(properties.getLiveWindowBefore()))
                .stream()
                .filter(game -> game.getState() == GameState.LIVE || game.getState() == GameState.SCHEDULED)
                .filter(game -> !followedIn(game, teams).isEmpty())
                .map(game -> toDto(game, teams))
                .toList();
    }

    /**
     * The panel's details for one game, read from its provider at most once a minute.
     *
     * @throws ResourceNotFoundException for a game not in this family's cache, or none available (404)
     */
    public GameSummary summary(Family family, UUID gameId) {
        SportsGame game = sportsGameRepository.findByIdAndFamily(gameId, family)
                .orElseThrow(() -> new ResourceNotFoundException("Game", gameId));
        String key = game.getLeague() + ":" + game.getProviderGameId();
        Instant now = Instant.now(clock);
        CachedSummary cached = summaries.get(key);
        if (cached == null || cached.fetchedAt().plus(properties.getSummaryTtl()).isBefore(now)) {
            Optional<GameSummary> fetched = providers.forLeague(game.getLeague())
                    .flatMap(p -> p.summary(game.getLeague(), game.getProviderGameId()));
            if (fetched.isEmpty()) {
                throw new ResourceNotFoundException("No details for this game yet");
            }
            cached = new CachedSummary(fetched.get(), now);
            summaries.put(key, cached);
        }
        return cached.summary();
    }

    private static List<FollowedTeam> followedIn(SportsGame game, List<FollowedTeam> teams) {
        return teams.stream()
                .filter(t -> t.getLeague() == game.getLeague())
                .filter(t -> t.getProviderTeamId().equals(game.getHomeTeamId())
                        || t.getProviderTeamId().equals(game.getAwayTeamId()))
                .toList();
    }

    private static SportsGameResponse toDto(SportsGame game, List<FollowedTeam> teams) {
        List<FollowedTeam> followed = followedIn(game, teams);
        return new SportsGameResponse(
                game.getId(),
                game.getProviderGameId(),
                game.getLeague(),
                game.getStartAt(),
                game.isTimeTbd(),
                side(game.getHomeTeamId(), game.getHomeName(), game.getHomeAbbreviation(), game.getHomeScore(), followed),
                side(game.getAwayTeamId(), game.getAwayName(), game.getAwayAbbreviation(), game.getAwayScore(), followed),
                game.getVenue(),
                game.getBroadcasts() == null ? List.of() : Arrays.asList(game.getBroadcasts().split("\n")),
                game.getState(),
                game.getDetail(),
                followed.stream().map(FollowedTeam::getId).toList(),
                followed.stream().map(FollowedTeam::getColor).filter(Objects::nonNull).findFirst().orElse(null),
                followed.stream().flatMap(t -> t.getFans().stream()).map(FamilyMember::getId).distinct()
                        .sorted(Comparator.naturalOrder()).toList(),
                followed.stream().anyMatch(FollowedTeam::isHideScores)
        );
    }

    private static SportsGameResponse.Side side(String teamId, String name, String abbreviation, Integer score,
                                                List<FollowedTeam> followed) {
        String logo = followed.stream().filter(t -> t.getProviderTeamId().equals(teamId))
                .map(FollowedTeam::getLogoUrl).findFirst().orElse(null);
        return new SportsGameResponse.Side(teamId, name, abbreviation, score, logo);
    }
}
