package com.familyhub.demo.sports;

import com.familyhub.demo.config.SportsProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The NFL from nflverse's open data (CC BY 4.0, github.com/nflverse): every game of the
 * season with its kickoff, published in US Eastern time, and its final score within
 * about a day. No key is needed. Two files are read at most once an hour and shared by
 * every team's schedule. Live scores come from {@link BigBallsLiveScores} when it has a key.
 */
@Component
public class NflverseProvider implements SportsDataProvider {
    private static final ZoneId KICKOFF_ZONE = ZoneId.of("America/New_York");
    /** A game with no kickoff time yet is placed at noon Eastern: the same date all across the US. */
    private static final LocalTime UNSET_KICKOFF = LocalTime.NOON;

    private final RestClient restClient;
    private final SportsProperties properties;
    private final BigBallsLiveScores live;
    private final Clock clock;
    private Files files;

    private record Game(String id, int season, LocalDate day, LocalTime time, String home, String away,
                        Integer homeScore, Integer awayScore, boolean overtime, String stadium) {}

    private record Team(String abbreviation, String name, String nickname, String logoUrl) {}

    /** Both files as last read; games are kept from the last two seasons only. */
    private record Files(List<Game> games, Map<String, Team> teams, int latestSeason, Instant readAt) {}

    @Autowired
    public NflverseProvider(SportsProperties properties, BigBallsLiveScores live, Clock clock) {
        this(SportsHttp.client(properties), properties, live, clock);
    }

    /** Test seam: a fully built client (for example bound to a MockRestServiceServer). */
    NflverseProvider(RestClient restClient, SportsProperties properties, BigBallsLiveScores live, Clock clock) {
        this.restClient = restClient;
        this.properties = properties;
        this.live = live;
        this.clock = clock;
    }

    @Override
    public Set<SportsLeague> leagues() {
        return Set.of(SportsLeague.NFL);
    }

    /** Today's teams: the file also lists old ones (the Oakland Raiders), which have no game in the latest season. */
    @Override
    public List<ProviderTeam> teams(SportsLeague league) {
        Files current = files();
        Set<String> playing = current.games().stream()
                .filter(game -> game.season() == current.latestSeason())
                .flatMap(game -> Stream.of(game.home(), game.away()))
                .collect(Collectors.toSet());
        return current.teams().values().stream()
                .filter(team -> playing.contains(team.abbreviation()))
                .map(team -> new ProviderTeam(team.abbreviation(), team.name(), team.nickname(), team.abbreviation(),
                        team.logoUrl()))
                .toList();
    }

    @Override
    public List<ProviderGame> schedule(SportsLeague league, String teamId, LocalDate from, LocalDate to) {
        Files current = files();
        return current.games().stream()
                .filter(game -> game.home().equals(teamId) || game.away().equals(teamId))
                .filter(game -> !game.day().isBefore(from) && !game.day().isAfter(to))
                .map(game -> toProviderGame(game, current.teams()))
                .toList();
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

    private synchronized Files files() {
        Instant now = Instant.now(clock);
        if (files == null || files.readAt().plus(properties.getNflverseCacheTtl()).isBefore(now)) {
            files = read(now);
        }
        return files;
    }

    private Files read(Instant now) {
        List<Map<String, String>> gameRows = Csv.rows(download(properties.getNflverseGamesUrl()));
        List<Map<String, String>> teamRows = Csv.rows(download(properties.getNflverseTeamsUrl()));
        int latest = gameRows.stream().mapToInt(row -> Integer.parseInt(row.get("season"))).max()
                .orElseThrow(() -> new IllegalStateException("nflverse's games file lists no games"));
        List<Game> games = new ArrayList<>();
        for (Map<String, String> row : gameRows) {
            int season = Integer.parseInt(row.get("season"));
            if (season >= latest - 1) {
                games.add(game(row, season));
            }
        }
        Map<String, Team> teams = teamRows.stream()
                .map(row -> new Team(row.get("team_abbr"), row.get("team_name"), blankToNull(row.get("team_nick")),
                        blankToNull(row.get("team_logo_wikipedia"))))
                .collect(Collectors.toMap(Team::abbreviation, Function.identity(), (first, second) -> first,
                        HashMap::new));
        return new Files(List.copyOf(games), Map.copyOf(teams), latest, now);
    }

    private String download(String url) {
        String body = restClient.get().uri(url).retrieve().body(String.class);
        if (body == null || body.isBlank()) {
            throw new IllegalStateException("Empty file from nflverse: " + url);
        }
        return body;
    }

    private static Game game(Map<String, String> row, int season) {
        String time = row.getOrDefault("gametime", "");
        return new Game(
                row.get("game_id"),
                season,
                LocalDate.parse(row.get("gameday")),
                time.isBlank() ? null : LocalTime.parse(time),
                row.get("home_team"),
                row.get("away_team"),
                integerOrNull(row.get("home_score")),
                integerOrNull(row.get("away_score")),
                "1".equals(row.get("overtime")),
                blankToNull(row.get("stadium")));
    }

    private static ProviderGame toProviderGame(Game game, Map<String, Team> teams) {
        boolean played = game.homeScore() != null && game.awayScore() != null;
        Instant kickoff = game.day().atTime(game.time() == null ? UNSET_KICKOFF : game.time())
                .atZone(KICKOFF_ZONE).toInstant();
        return new ProviderGame(
                game.id(),
                SportsLeague.NFL,
                kickoff,
                game.time() == null,
                side(game.home(), game.homeScore(), teams),
                side(game.away(), game.awayScore(), teams),
                game.stadium(),
                List.of(),
                played ? GameState.FINAL : GameState.SCHEDULED,
                played ? (game.overtime() ? "Final/OT" : "Final") : null);
    }

    private static GameSide side(String abbreviation, Integer score, Map<String, Team> teams) {
        Team team = teams.get(abbreviation);
        return new GameSide(abbreviation, team == null ? abbreviation : team.name(), abbreviation, score);
    }

    private static Integer integerOrNull(String value) {
        return value == null || value.isBlank() ? null : Integer.valueOf(value.trim());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }

    /** Just enough CSV for nflverse's files: a header row, commas, and quoted fields that may hold commas. */
    private static final class Csv {
        private Csv() {
        }

        static List<Map<String, String>> rows(String text) {
            String[] lines = text.split("\r?\n");
            List<String> header = fields(lines[0]);
            List<Map<String, String>> rows = new ArrayList<>();
            for (int i = 1; i < lines.length; i++) {
                if (lines[i].isBlank()) {
                    continue;
                }
                List<String> values = fields(lines[i]);
                Map<String, String> row = new HashMap<>();
                for (int column = 0; column < header.size(); column++) {
                    row.put(header.get(column), column < values.size() ? values.get(column) : "");
                }
                rows.add(row);
            }
            return rows;
        }

        private static List<String> fields(String line) {
            List<String> fields = new ArrayList<>();
            StringBuilder field = new StringBuilder();
            boolean quoted = false;
            for (int i = 0; i < line.length(); i++) {
                char c = line.charAt(i);
                if (quoted) {
                    if (c == '"' && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                        field.append('"');
                        i++;
                    } else if (c == '"') {
                        quoted = false;
                    } else {
                        field.append(c);
                    }
                } else if (c == '"') {
                    quoted = true;
                } else if (c == ',') {
                    fields.add(field.toString());
                    field.setLength(0);
                } else {
                    field.append(c);
                }
            }
            fields.add(field.toString());
            return fields;
        }
    }
}
