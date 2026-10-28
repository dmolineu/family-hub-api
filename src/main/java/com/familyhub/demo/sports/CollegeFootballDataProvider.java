package com.familyhub.demo.sports;

import com.familyhub.demo.config.SportsProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

/**
 * College football from CollegeFootballData ({@code api.collegefootballdata.com}): FBS
 * and FCS teams, each team's season with its TV channels, and final scores once a game
 * is over. Its free key (requested by email, no card) allows 1,000 requests a month,
 * shared with the basketball API; a followed team costs two a day. Without a key the
 * league has no source. Live scores come from {@link BigBallsLiveScores} when it has a key.
 */
@Component
public class CollegeFootballDataProvider implements SportsDataProvider {
    /** Divisions a family is likely to follow; Division II and III are left out of the search. */
    private static final Set<String> CLASSIFICATIONS = Set.of("fbs", "fcs");
    private static final int REGULATION_QUARTERS = 4;
    private static final int MAX_BROADCASTS = 3;
    /** From June the coming season's schedule is read as well as the one before it. */
    private static final int LOOK_AHEAD_DAYS = 90;

    private final RestClient restClient;
    private final SportsProperties properties;
    private final BigBallsLiveScores live;
    private final Clock clock;
    private Teams teams;

    private record Team(String id, String school, String mascot, String abbreviation, String logoUrl) {}

    private record Teams(Map<String, Team> byId, Instant readAt) {}

    @Autowired
    public CollegeFootballDataProvider(SportsProperties properties, BigBallsLiveScores live, Clock clock) {
        this(SportsHttp.client(properties), properties, live, clock);
    }

    /** Test seam: a fully built client (for example bound to a MockRestServiceServer). */
    CollegeFootballDataProvider(RestClient restClient, SportsProperties properties, BigBallsLiveScores live,
                                Clock clock) {
        this.restClient = restClient;
        this.properties = properties;
        this.live = live;
        this.clock = clock;
    }

    @Override
    public Set<SportsLeague> leagues() {
        return properties.getCfbdApiKey().isBlank() ? Set.of() : Set.of(SportsLeague.COLLEGE_FOOTBALL);
    }

    @Override
    public List<ProviderTeam> teams(SportsLeague league) {
        return teams().byId().values().stream()
                .map(team -> new ProviderTeam(team.id(),
                        team.mascot() == null ? team.school() : team.school() + " " + team.mascot(),
                        team.school(), team.abbreviation(), team.logoUrl()))
                .toList();
    }

    /** The team's games in the season the window starts in, and in the coming one from June. */
    @Override
    public List<ProviderGame> schedule(SportsLeague league, String teamId, LocalDate from, LocalDate to) {
        Teams known = teams();
        Team team = Optional.ofNullable(known.byId().get(teamId)).orElseThrow(
                () -> new IllegalArgumentException("No college football team " + teamId + " in CollegeFootballData"));
        Set<Integer> seasons = new TreeSet<>(List.of(season(from), season(from.plusDays(LOOK_AHEAD_DAYS))));
        List<ProviderGame> games = new ArrayList<>();
        for (int season : seasons) {
            Map<String, Set<String>> channels = channels(season, team.school());
            for (JsonNode game : get("/games?year={year}&team={team}&seasonType=both", season, team.school())) {
                Instant start = Instant.parse(game.path("startDate").asText());
                LocalDate day = LocalDate.ofInstant(start, ZoneOffset.UTC);
                if (!day.isBefore(from) && !day.isAfter(to)) {
                    games.add(game(game, start, known, channels));
                }
            }
        }
        return games;
    }

    @Override
    public List<ProviderGame> games(SportsLeague league, Collection<ProviderGame> known) {
        return live.covers(league) ? live.refresh(league, known) : List.of();
    }

    @Override
    public Optional<GameSummary> summary(SportsLeague league, String gameId) {
        return Optional.empty();
    }

    @Override
    public boolean liveScores(SportsLeague league) {
        return live.covers(league);
    }

    @Override
    public Duration liveRefreshInterval(SportsLeague league) {
        return live.refreshInterval();
    }

    // ----------------------------------------------------------------- reading

    /** FBS and FCS teams, read again after {@code sports.team-list-ttl}. */
    private synchronized Teams teams() {
        Instant now = Instant.now(clock);
        if (teams == null || teams.readAt().plus(properties.getTeamListTtl()).isBefore(now)) {
            Map<String, Team> byId = new HashMap<>();
            for (JsonNode team : get("/teams?year={year}", season(LocalDate.now(clock)))) {
                if (CLASSIFICATIONS.contains(team.path("classification").asText(""))) {
                    String id = team.path("id").asText();
                    byId.put(id, new Team(id, team.path("school").asText(), textOrNull(team, "mascot"),
                            textOrNull(team, "abbreviation"), firstLogo(team.path("logos"))));
                }
            }
            teams = new Teams(Map.copyOf(byId), now);
        }
        return teams;
    }

    /** Each game's TV channels, once each. */
    private Map<String, Set<String>> channels(int season, String school) {
        Map<String, Set<String>> channels = new HashMap<>();
        for (JsonNode media : get("/games/media?year={year}&team={team}&seasonType=both&mediaType=tv",
                season, school)) {
            String outlet = textOrNull(media, "outlet");
            if (outlet != null) {
                channels.computeIfAbsent(media.path("id").asText(), id -> new LinkedHashSet<>()).add(outlet);
            }
        }
        return channels;
    }

    private JsonNode get(String pathAndQuery, Object... variables) {
        JsonNode body = restClient.get()
                .uri(properties.getCfbdBaseUrl() + pathAndQuery, variables)
                .header("Authorization", "Bearer " + properties.getCfbdApiKey())
                .retrieve()
                .body(JsonNode.class);
        if (body == null || !body.isArray()) {
            throw new IllegalStateException("Unexpected response from CollegeFootballData: " + pathAndQuery);
        }
        return body;
    }

    private static ProviderGame game(JsonNode game, Instant start, Teams teams, Map<String, Set<String>> channels) {
        String id = game.path("id").asText();
        boolean completed = game.path("completed").asBoolean(false);
        boolean overtime = game.path("homeLineScores").size() > REGULATION_QUARTERS;
        return new ProviderGame(
                id,
                SportsLeague.COLLEGE_FOOTBALL,
                start,
                game.path("startTimeTBD").asBoolean(false),
                side(game, "home", completed, teams),
                side(game, "away", completed, teams),
                textOrNull(game, "venue"),
                channels.getOrDefault(id, Set.of()).stream().limit(MAX_BROADCASTS).toList(),
                completed ? GameState.FINAL : GameState.SCHEDULED,
                completed ? (overtime ? "Final/OT" : "Final") : null);
    }

    /** A side named by its school, as a family says it ("San Diego State vs Nevada"). */
    private static GameSide side(JsonNode game, String side, boolean completed, Teams teams) {
        String teamId = game.path(side + "Id").asText();
        Team team = teams.byId().get(teamId);
        JsonNode points = game.path(side + "Points");
        return new GameSide(teamId, game.path(side + "Team").asText(), team == null ? null : team.abbreviation(),
                completed && points.isNumber() ? points.asInt() : null);
    }

    /** A football season runs from August into January and is named for the year it starts. */
    private static int season(LocalDate date) {
        return date.getMonthValue() >= 7 ? date.getYear() : date.getYear() - 1;
    }

    private static String firstLogo(JsonNode logos) {
        return logos.isArray() && !logos.isEmpty() ? logos.get(0).asText() : null;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }
}
