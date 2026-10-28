package com.familyhub.demo.sports;

import com.familyhub.demo.config.SportsProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import tools.jackson.databind.JsonNode;

import java.text.Normalizer;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Live NFL and college football scores from BigBalls ({@code api.bigballsdata.com}). Its
 * terms let an app show the data and keep it; its free plan covers one sport (football)
 * with 250 requests a day, so games are refreshed every few minutes, with one request per
 * kickoff day. BigBalls has its own ids and names, so each game the app already knows is
 * found by its two teams' names and a kickoff within a few hours. Off without a key.
 */
@Component
public class BigBallsLiveScores {
    private static final Map<SportsLeague, String> LEAGUE_CODES = Map.of(
            SportsLeague.NFL, "nfl",
            SportsLeague.COLLEGE_FOOTBALL, "ncaaf");
    /** One football Saturday fits in a page or two. */
    private static final int PAGE_SIZE = 200;
    private static final int MAX_PAGES = 3;
    /** A game whose kickoff is further apart than this is another game. */
    private static final Duration KICKOFF_TOLERANCE = Duration.ofHours(4);
    private static final int REGULATION_QUARTERS = 4;
    private static final int EXACT_NAME = 1000;

    private final RestClient restClient;
    private final SportsProperties properties;

    @Autowired
    public BigBallsLiveScores(SportsProperties properties) {
        this(SportsHttp.client(properties), properties);
    }

    /** Test seam: a fully built client (for example bound to a MockRestServiceServer). */
    BigBallsLiveScores(RestClient restClient, SportsProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    /** Whether live scores for this league can be read: a key is set and the free plan's sport covers it. */
    public boolean covers(SportsLeague league) {
        return !properties.getBigballsApiKey().isBlank() && LEAGUE_CODES.containsKey(league);
    }

    /** How often live games may be refreshed within the plan's daily allowance. */
    public Duration refreshInterval() {
        return properties.getBigballsRefreshInterval();
    }

    /**
     * The games' current scores and states, keeping the app's own ids, names and kickoff.
     * Games BigBalls does not list, or lists ambiguously, are left out (and so unchanged).
     */
    public List<ProviderGame> refresh(SportsLeague league, Collection<ProviderGame> known) {
        String code = LEAGUE_CODES.get(league);
        if (code == null || known.isEmpty()) {
            return List.of();
        }
        Map<LocalDate, List<ProviderGame>> byKickoffDay = known.stream().collect(Collectors.groupingBy(
                game -> LocalDate.ofInstant(game.startAt(), ZoneOffset.UTC), TreeMap::new, Collectors.toList()));
        List<ProviderGame> refreshed = new ArrayList<>();
        byKickoffDay.forEach((day, games) -> {
            List<JsonNode> matches = matches(code, day);
            for (ProviderGame game : games) {
                bestMatch(game, matches).flatMap(found -> apply(game, found)).ifPresent(refreshed::add);
            }
        });
        return refreshed;
    }

    // ----------------------------------------------------------------- reading

    /** One UTC day's games in a league (BigBalls' days are UTC days). */
    private List<JsonNode> matches(String code, LocalDate day) {
        List<JsonNode> matches = new ArrayList<>();
        for (int page = 0; page < MAX_PAGES; page++) {
            JsonNode body = restClient.get()
                    .uri(properties.getBigballsBaseUrl()
                                    + "/v1/matches?sport=american_football&league={league}&date={date}&limit={limit}"
                                    + "&offset={offset}",
                            code, day, PAGE_SIZE, page * PAGE_SIZE)
                    .header("x-api-key", properties.getBigballsApiKey())
                    .retrieve()
                    .body(JsonNode.class);
            JsonNode data = body == null ? null : body.path("data");
            if (data == null || !data.isArray()) {
                break;
            }
            data.forEach(matches::add);
            if (data.size() < PAGE_SIZE) {
                break;
            }
        }
        return matches;
    }

    // ----------------------------------------------------------------- matching

    /** A listed game, and whether it names the sides the other way round (as at a neutral site). */
    private record Found(JsonNode match, boolean swapped) {}

    /**
     * The listed game whose teams' names fit this game's best, kicking off near it. Both
     * sides must fit; a tie between two listed games is ambiguous and finds nothing,
     * because no score is better than another game's score.
     */
    private static Optional<Found> bestMatch(ProviderGame game, List<JsonNode> matches) {
        Found best = null;
        int bestScore = 0;
        boolean tied = false;
        for (JsonNode match : matches) {
            Instant kickoff = Instant.parse(match.path("kickoff_utc").asText());
            if (Duration.between(kickoff, game.startAt()).abs().compareTo(KICKOFF_TOLERANCE) > 0) {
                continue;
            }
            for (boolean swapped : new boolean[] {false, true}) {
                int home = sideScore(game.home().name(), match.path(swapped ? "away" : "home"));
                int away = sideScore(game.away().name(), match.path(swapped ? "home" : "away"));
                if (home == 0 || away == 0) {
                    continue;
                }
                int score = home + away;
                if (score > bestScore) {
                    best = new Found(match, swapped);
                    bestScore = score;
                    tied = false;
                } else if (score == bestScore) {
                    tied = true;
                }
            }
        }
        return tied ? Optional.empty() : Optional.ofNullable(best);
    }

    private static int sideScore(String ours, JsonNode side) {
        return Math.max(nameScore(ours, side.path("name").asText("")),
                nameScore(ours, side.path("short_name").asText("")));
    }

    /**
     * How well two names fit: the same words score highest; one name starting with the
     * other's words ("San Diego State" and "San Diego State Aztecs") scores by the words
     * shared, less one for each extra word, so "Texas Longhorns" fits "Texas" better than
     * "Texas Tech Red Raiders" does; anything else is no fit.
     */
    private static int nameScore(String ours, String theirs) {
        List<String> a = words(ours);
        List<String> b = words(theirs);
        if (a.isEmpty() || b.isEmpty()) {
            return 0;
        }
        if (a.equals(b)) {
            return EXACT_NAME;
        }
        List<String> shorter = a.size() <= b.size() ? a : b;
        List<String> longer = a.size() <= b.size() ? b : a;
        if (!longer.subList(0, shorter.size()).equals(shorter)) {
            return 0;
        }
        return 100 * shorter.size() - (longer.size() - shorter.size());
    }

    /**
     * "San José St." and "San Jose State" are the same words (the accent, split off, goes
     * with the punctuation); so are "Hawai'i", "Hawaiʻi" (with the ʻokina) and "Hawaii".
     */
    private static List<String> words(String name) {
        String plain = Normalizer.normalize(name, Normalizer.Form.NFD)
                .toLowerCase(Locale.ROOT)
                .replaceAll("['\u2019\u02bb]", "")
                .replace("&", " and ")
                .replaceAll("[^a-z0-9]+", " ")
                .trim();
        if (plain.isEmpty()) {
            return List.of();
        }
        return Arrays.stream(plain.split(" ")).map(word -> word.equals("st") ? "state" : word).toList();
    }

    // ----------------------------------------------------------------- applying

    private static Optional<ProviderGame> apply(ProviderGame game, Found found) {
        JsonNode match = found.match();
        Optional<GameState> state = state(match.path("status").asText(""));
        if (state.isEmpty()) {
            return Optional.empty();
        }
        Integer theirHome = score(match.path("score").path("home"));
        Integer theirAway = score(match.path("score").path("away"));
        Integer home = found.swapped() ? theirAway : theirHome;
        Integer away = found.swapped() ? theirHome : theirAway;
        String broadcast = match.path("broadcast").asText("");
        List<String> broadcasts = game.broadcasts().isEmpty() && !broadcast.isBlank()
                ? List.of(broadcast) : game.broadcasts();
        return Optional.of(new ProviderGame(
                game.id(),
                game.league(),
                game.startAt(),
                game.timeTbd(),
                new GameSide(game.home().teamId(), game.home().name(), game.home().abbreviation(), home),
                new GameSide(game.away().teamId(), game.away().name(), game.away().abbreviation(), away),
                game.venue(),
                broadcasts,
                state.get(),
                detail(state.get(), periods(match.path("linescore")))));
    }

    /** BigBalls' status words; an unknown one leaves the game as it was. */
    private static Optional<GameState> state(String status) {
        return Optional.ofNullable(switch (status) {
            case "scheduled" -> GameState.SCHEDULED;
            case "live", "in_progress" -> GameState.LIVE;
            case "finished" -> GameState.FINAL;
            case "postponed" -> GameState.POSTPONED;
            case "cancelled" -> GameState.CANCELLED;
            case "suspended" -> GameState.SUSPENDED;
            default -> null;
        });
    }

    /** "Q3" or "OT" while on, "Final" or "Final/OT" after. */
    private static String detail(GameState state, int periods) {
        return switch (state) {
            case LIVE -> periods > REGULATION_QUARTERS ? "OT" : periods > 0 ? "Q" + periods : null;
            case FINAL -> periods > REGULATION_QUARTERS ? "Final/OT" : "Final";
            case POSTPONED -> "Postponed";
            case CANCELLED -> "Cancelled";
            case SUSPENDED -> "Suspended";
            case SCHEDULED -> null;
        };
    }

    /** Periods begun so far: the scored entries in the longer side's line. */
    private static int periods(JsonNode linescore) {
        int periods = 0;
        for (String side : new String[] {"home", "away"}) {
            int count = 0;
            for (JsonNode period : linescore.path(side)) {
                if (!period.isNull()) {
                    count++;
                }
            }
            periods = Math.max(periods, count);
        }
        return periods;
    }

    private static Integer score(JsonNode value) {
        return value.isNumber() ? value.asInt() : null;
    }
}
