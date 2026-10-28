package com.familyhub.demo.sports;

import com.familyhub.demo.config.SportsProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.time.Clock;
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

/**
 * Men's college basketball from CollegeBasketballData ({@code api.collegebasketballdata.com}),
 * the basketball twin of CollegeFootballData, with the same key and the same monthly
 * allowance: teams, each team's games between two dates with their TV channels, and final
 * scores. There are no live scores: BigBalls' free plan covers football only.
 */
@Component
public class CollegeBasketballDataProvider implements SportsDataProvider {
    /** Games that went past the two halves. */
    private static final int REGULATION_HALVES = 2;
    private static final int MAX_BROADCASTS = 3;

    private final RestClient restClient;
    private final SportsProperties properties;
    private final Clock clock;
    private Teams teams;

    private record Team(String id, String school, String name, String abbreviation, String logoUrl) {}

    private record Teams(Map<String, Team> byId, Instant readAt) {}

    @Autowired
    public CollegeBasketballDataProvider(SportsProperties properties, Clock clock) {
        this(SportsHttp.client(properties), properties, clock);
    }

    /** Test seam: a fully built client (for example bound to a MockRestServiceServer). */
    CollegeBasketballDataProvider(RestClient restClient, SportsProperties properties, Clock clock) {
        this.restClient = restClient;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Set<SportsLeague> leagues() {
        return properties.getCfbdApiKey().isBlank() ? Set.of() : Set.of(SportsLeague.MENS_COLLEGE_BASKETBALL);
    }

    @Override
    public List<ProviderTeam> teams(SportsLeague league) {
        return teams().byId().values().stream()
                .map(team -> new ProviderTeam(team.id(), team.name(), team.school(), team.abbreviation(), team.logoUrl()))
                .toList();
    }

    @Override
    public List<ProviderGame> schedule(SportsLeague league, String teamId, LocalDate from, LocalDate to) {
        Teams known = teams();
        Team team = Optional.ofNullable(known.byId().get(teamId)).orElseThrow(() -> new IllegalArgumentException(
                "No men's college basketball team " + teamId + " in CollegeBasketballData"));
        String start = from.atStartOfDay(ZoneOffset.UTC).toInstant().toString();
        String end = to.plusDays(1).atStartOfDay(ZoneOffset.UTC).toInstant().toString();
        Map<String, Set<String>> channels = channels(team.school(), start, end);
        List<ProviderGame> games = new ArrayList<>();
        for (JsonNode game : get("/games?team={team}&startDateRange={start}&endDateRange={end}",
                team.school(), start, end)) {
            games.add(game(game, known, channels));
        }
        return games;
    }

    @Override
    public List<ProviderGame> games(SportsLeague league, Collection<ProviderGame> known) {
        return List.of();
    }

    @Override
    public Optional<GameSummary> summary(SportsLeague league, String gameId) {
        return Optional.empty();
    }

    @Override
    public boolean liveScores(SportsLeague league) {
        return false;
    }

    // ----------------------------------------------------------------- reading

    /** The season's teams, read again after {@code sports.team-list-ttl}. */
    private synchronized Teams teams() {
        Instant now = Instant.now(clock);
        if (teams == null || teams.readAt().plus(properties.getTeamListTtl()).isBefore(now)) {
            Map<String, Team> byId = new HashMap<>();
            for (JsonNode team : get("/teams?season={season}", season(LocalDate.now(clock)))) {
                String id = team.path("id").asText();
                String school = team.path("school").asText();
                String mascot = textOrNull(team, "mascot");
                String name = Optional.ofNullable(textOrNull(team, "displayName"))
                        .orElse(mascot == null ? school : school + " " + mascot);
                JsonNode logos = team.path("logos");
                byId.put(id, new Team(id, school, name, textOrNull(team, "abbreviation"),
                        logos.isArray() && !logos.isEmpty() ? logos.get(0).asText() : null));
            }
            teams = new Teams(Map.copyOf(byId), now);
        }
        return teams;
    }

    /** Each game's television channels, once each; radio stations are left out. */
    private Map<String, Set<String>> channels(String school, String start, String end) {
        Map<String, Set<String>> channels = new HashMap<>();
        for (JsonNode media : get("/games/media?team={team}&startDateRange={start}&endDateRange={end}",
                school, start, end)) {
            for (JsonNode broadcast : media.path("broadcasts")) {
                String name = textOrNull(broadcast, "broadcastName");
                if (name != null && !"radio".equalsIgnoreCase(broadcast.path("broadcastType").asText(""))) {
                    channels.computeIfAbsent(media.path("gameId").asText(), id -> new LinkedHashSet<>()).add(name);
                }
            }
        }
        return channels;
    }

    private JsonNode get(String pathAndQuery, Object... variables) {
        JsonNode body = restClient.get()
                .uri(properties.getCbbdBaseUrl() + pathAndQuery, variables)
                .header("Authorization", "Bearer " + properties.getCfbdApiKey())
                .retrieve()
                .body(JsonNode.class);
        if (body == null || !body.isArray()) {
            throw new IllegalStateException("Unexpected response from CollegeBasketballData: " + pathAndQuery);
        }
        return body;
    }

    private static ProviderGame game(JsonNode game, Teams teams, Map<String, Set<String>> channels) {
        String id = game.path("id").asText();
        GameState state = switch (game.path("status").asText("")) {
            case "in_progress" -> GameState.LIVE;
            case "final" -> GameState.FINAL;
            case "postponed" -> GameState.POSTPONED;
            case "cancelled" -> GameState.CANCELLED;
            default -> GameState.SCHEDULED;
        };
        boolean scored = state == GameState.LIVE || state == GameState.FINAL;
        String detail = switch (state) {
            case FINAL -> game.path("homePeriodPoints").size() > REGULATION_HALVES ? "Final/OT" : "Final";
            case POSTPONED -> "Postponed";
            case CANCELLED -> "Cancelled";
            default -> null;
        };
        return new ProviderGame(
                id,
                SportsLeague.MENS_COLLEGE_BASKETBALL,
                Instant.parse(game.path("startDate").asText()),
                game.path("startTimeTbd").asBoolean(false),
                side(game, "home", scored, teams),
                side(game, "away", scored, teams),
                textOrNull(game, "venue"),
                channels.getOrDefault(id, Set.of()).stream().limit(MAX_BROADCASTS).toList(),
                state,
                detail);
    }

    /** A side named by its school, as a family says it ("San Diego State vs Gonzaga"). */
    private static GameSide side(JsonNode game, String side, boolean scored, Teams teams) {
        String teamId = game.path(side + "TeamId").asText();
        Team team = teams.byId().get(teamId);
        JsonNode points = game.path(side + "Points");
        return new GameSide(teamId, game.path(side + "Team").asText(), team == null ? null : team.abbreviation(),
                scored && points.isNumber() ? points.asInt() : null);
    }

    /** A basketball season runs from November into April and is named for the year it ends. */
    private static int season(LocalDate date) {
        return date.getMonthValue() >= 7 ? date.getYear() + 1 : date.getYear();
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }
}
