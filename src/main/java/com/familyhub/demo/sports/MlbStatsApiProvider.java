package com.familyhub.demo.sports;

import com.familyhub.demo.config.SportsProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * MLB's official Stats API ({@code statsapi.mlb.com}): its terms allow individual,
 * non-commercial use, which a family's own wall is. No key is needed. Its responses
 * carry no team colours, and logos come from MLB's image host by URL.
 */
@Component
public class MlbStatsApiProvider implements SportsDataProvider {
    /** Regular season and every postseason round; not spring training or exhibitions. */
    private static final String GAME_TYPES = "R,F,D,L,W";
    private static final String HYDRATE = "broadcasts(all),linescore,team,venue";
    private static final int MAX_BROADCASTS = 3;

    private final RestClient restClient;
    private final SportsProperties properties;

    @Autowired
    public MlbStatsApiProvider(SportsProperties properties) {
        this(RestClient.builder().requestFactory(requestFactory(properties)).build(), properties);
    }

    /** Test seam: a fully built client (for example bound to a MockRestServiceServer). */
    MlbStatsApiProvider(RestClient restClient, SportsProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    private static JdkClientHttpRequestFactory requestFactory(SportsProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder().connectTimeout(properties.getConnectTimeout()).build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.getReadTimeout());
        return factory;
    }

    @Override
    public Set<SportsLeague> leagues() {
        return Set.of(SportsLeague.MLB);
    }

    @Override
    public List<ProviderTeam> teams(SportsLeague league) {
        JsonNode body = get(properties.getMlbBaseUrl() + "/api/v1/teams?sportId=1");
        List<ProviderTeam> teams = new ArrayList<>();
        for (JsonNode team : body.path("teams")) {
            String id = team.path("id").asText();
            teams.add(new ProviderTeam(
                    id,
                    team.path("name").asText(),
                    firstText(team, "clubName", "teamName", "name"),
                    team.path("abbreviation").asText(),
                    properties.getMlbLogoUrl().replace("{id}", id)
            ));
        }
        return teams;
    }

    @Override
    public List<ProviderGame> schedule(SportsLeague league, String teamId, LocalDate from, LocalDate to) {
        return games(restClient.get()
                .uri(properties.getMlbBaseUrl() + "/api/v1/schedule?sportId=1&teamId={team}&startDate={from}"
                                + "&endDate={to}&gameType={types}&hydrate={hydrate}",
                        teamId, from, to, GAME_TYPES, HYDRATE)
                .retrieve()
                .body(JsonNode.class));
    }

    @Override
    public List<ProviderGame> games(SportsLeague league, Collection<ProviderGame> known) {
        if (known.isEmpty()) {
            return List.of();
        }
        String gameIds = known.stream().map(ProviderGame::id).distinct().collect(Collectors.joining(","));
        return games(restClient.get()
                .uri(properties.getMlbBaseUrl() + "/api/v1/schedule?sportId=1&gamePks={ids}&hydrate={hydrate}",
                        gameIds, "linescore,team")
                .retrieve()
                .body(JsonNode.class));
    }

    @Override
    public Optional<GameSummary> summary(SportsLeague league, String gameId) {
        JsonNode linescore = get(properties.getMlbBaseUrl() + "/api/v1/game/" + gameId + "/linescore");
        JsonNode boxscore = get(properties.getMlbBaseUrl() + "/api/v1/game/" + gameId + "/boxscore");

        String awayAbbreviation = boxscore.path("teams").path("away").path("team").path("abbreviation").asText("Away");
        String homeAbbreviation = boxscore.path("teams").path("home").path("team").path("abbreviation").asText("Home");
        List<GameSummary.TeamLine> lines = List.of(
                line(awayAbbreviation, linescore.path("teams").path("away")),
                line(homeAbbreviation, linescore.path("teams").path("home")));

        List<String> situation = new ArrayList<>();
        if (linescore.has("balls") && linescore.has("outs")) {
            int outs = linescore.path("outs").asInt();
            situation.add("%d-%d, %d out%s".formatted(linescore.path("balls").asInt(),
                    linescore.path("strikes").asInt(), outs, outs == 1 ? "" : "s"));
        }
        JsonNode offense = linescore.path("offense");
        List<String> bases = new ArrayList<>();
        for (String[] base : new String[][] {{"first", "1st"}, {"second", "2nd"}, {"third", "3rd"}}) {
            if (offense.has(base[0])) {
                bases.add(base[1]);
            }
        }
        if (!bases.isEmpty()) {
            situation.add((bases.size() == 1 ? "Runner on " : "Runners on ") + joinWithAnd(bases));
        }
        if (offense.path("batter").has("fullName")) {
            situation.add("At bat: " + offense.path("batter").path("fullName").asText());
        }
        JsonNode pitcher = linescore.path("defense").path("pitcher");
        if (pitcher.has("fullName")) {
            situation.add("Pitching: " + pitcher.path("fullName").asText());
        }

        List<GameSummary.Leader> leaders = new ArrayList<>();
        for (JsonNode performer : boxscore.path("topPerformers")) {
            JsonNode player = performer.path("player");
            String name = player.path("person").path("fullName").asText();
            JsonNode stats = player.path("stats");
            if (stats.path("batting").hasNonNull("summary")) {
                leaders.add(new GameSummary.Leader("Batting", name, stats.path("batting").path("summary").asText()));
            } else if (stats.path("pitching").hasNonNull("summary")) {
                leaders.add(new GameSummary.Leader("Pitching", name, stats.path("pitching").path("summary").asText()));
            }
        }
        return Optional.of(new GameSummary(gameId, liveDetail(linescore), lines, situation, leaders));
    }

    // ----------------------------------------------------------------- parsing

    private List<ProviderGame> games(JsonNode body) {
        List<ProviderGame> games = new ArrayList<>();
        if (body == null) {
            return games;
        }
        for (JsonNode date : body.path("dates")) {
            for (JsonNode game : date.path("games")) {
                games.add(game(game));
            }
        }
        return games;
    }

    private ProviderGame game(JsonNode game) {
        JsonNode status = game.path("status");
        GameState state = state(status);
        boolean scored = state == GameState.LIVE || state == GameState.FINAL || state == GameState.SUSPENDED;
        return new ProviderGame(
                game.path("gamePk").asText(),
                SportsLeague.MLB,
                Instant.parse(game.path("gameDate").asText()),
                status.path("startTimeTBD").asBoolean(false),
                side(game.path("teams").path("home"), scored),
                side(game.path("teams").path("away"), scored),
                textOrNull(game.path("venue"), "name"),
                broadcasts(game.path("broadcasts")),
                state,
                detail(state, status, game.path("linescore"))
        );
    }

    /**
     * MLB reports a postponed or cancelled game as "Final" overall, so the coded state
     * decides first; the overall state only separates scheduled, live and final.
     */
    private static GameState state(JsonNode status) {
        String coded = status.path("codedGameState").asText("");
        String detailed = status.path("detailedState").asText("");
        if (coded.equals("D") || detailed.startsWith("Postponed")) {
            return GameState.POSTPONED;
        }
        if (coded.equals("C") || detailed.startsWith("Cancelled")) {
            return GameState.CANCELLED;
        }
        if (detailed.startsWith("Suspended")) {
            return GameState.SUSPENDED;
        }
        return switch (status.path("abstractGameState").asText("")) {
            case "Live" -> GameState.LIVE;
            case "Final" -> GameState.FINAL;
            default -> GameState.SCHEDULED;
        };
    }

    private static String detail(GameState state, JsonNode status, JsonNode linescore) {
        return switch (state) {
            case LIVE -> liveDetail(linescore);
            case FINAL -> {
                int innings = linescore.path("currentInning").asInt(0);
                int scheduled = linescore.path("scheduledInnings").asInt(9);
                yield innings > scheduled ? "Final/" + innings : "Final";
            }
            case POSTPONED, CANCELLED, SUSPENDED -> {
                String reason = textOrNull(status, "reason");
                String label = switch (state) {
                    case POSTPONED -> "Postponed";
                    case CANCELLED -> "Cancelled";
                    default -> "Suspended";
                };
                yield reason == null ? label : label + ": " + reason;
            }
            case SCHEDULED -> null;
        };
    }

    /** "Top 5th", "Middle 7th"; null when the linescore has not started. */
    private static String liveDetail(JsonNode linescore) {
        String half = textOrNull(linescore, "inningState");
        String inning = textOrNull(linescore, "currentInningOrdinal");
        return half == null || inning == null ? null : half + " " + inning;
    }

    private static GameSide side(JsonNode side, boolean scored) {
        JsonNode team = side.path("team");
        return new GameSide(
                team.path("id").asText(),
                team.path("name").asText(),
                textOrNull(team, "abbreviation"),
                scored && side.has("score") ? side.path("score").asInt() : null
        );
    }

    /** TV channels once each: MLB lists every broadcast per side and per media type. */
    private static List<String> broadcasts(JsonNode broadcasts) {
        Set<String> names = new LinkedHashSet<>();
        for (JsonNode broadcast : broadcasts) {
            if ("TV".equals(broadcast.path("type").asText()) && broadcast.hasNonNull("name")) {
                names.add(broadcast.path("name").asText());
            }
        }
        return names.stream().limit(MAX_BROADCASTS).toList();
    }

    private static GameSummary.TeamLine line(String abbreviation, JsonNode totals) {
        return new GameSummary.TeamLine(abbreviation, List.of(
                new GameSummary.Stat("R", totals.path("runs").asText("0")),
                new GameSummary.Stat("H", totals.path("hits").asText("0")),
                new GameSummary.Stat("E", totals.path("errors").asText("0"))));
    }

    // ----------------------------------------------------------------- helpers

    private JsonNode get(String url) {
        JsonNode body = restClient.get().uri(url).retrieve().body(JsonNode.class);
        if (body == null) {
            throw new IllegalStateException("Empty response from MLB Stats API: " + url);
        }
        return body;
    }

    private static String firstText(JsonNode node, String... fields) {
        for (String field : fields) {
            String value = textOrNull(node, field);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() || value.asText().isBlank() ? null : value.asText();
    }

    private static String joinWithAnd(List<String> items) {
        if (items.size() <= 1) {
            return String.join("", items);
        }
        return String.join(", ", items.subList(0, items.size() - 1)) + " and " + items.getLast();
    }
}
