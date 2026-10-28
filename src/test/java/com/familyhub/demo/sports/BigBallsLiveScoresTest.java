package com.familyhub.demo.sports;

import com.familyhub.demo.config.SportsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Matching BigBalls' live games to the games the app already knows, against hand-made fixtures. */
class BigBallsLiveScoresTest {
    private static final String MATCHES = "https://api.bigballsdata.com/v1/matches?";

    private MockRestServiceServer server;

    private BigBallsLiveScores liveScores(String key) {
        SportsProperties properties = new SportsProperties();
        properties.setBigballsApiKey(key);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        return new BigBallsLiveScores(builder.build(), properties);
    }

    private static String fixture(String name) throws IOException {
        return new ClassPathResource("sports/bigballs/" + name).getContentAsString(StandardCharsets.UTF_8);
    }

    private static ProviderGame known(String id, SportsLeague league, String kickoff, String home, String away) {
        return new ProviderGame(id, league, Instant.parse(kickoff), false,
                new GameSide("h-" + id, home, null, null), new GameSide("a-" + id, away, null, null),
                "Somewhere", List.of(), GameState.SCHEDULED, null);
    }

    @Test
    void coversNflAndCollegeFootballOnlyWithAKey() {
        assertThat(liveScores("").covers(SportsLeague.NFL)).isFalse();
        assertThat(liveScores("").covers(SportsLeague.COLLEGE_FOOTBALL)).isFalse();

        BigBallsLiveScores withKey = liveScores("bb-key");
        assertThat(withKey.covers(SportsLeague.NFL)).isTrue();
        assertThat(withKey.covers(SportsLeague.COLLEGE_FOOTBALL)).isTrue();
        // The free plan is one sport: football.
        assertThat(withKey.covers(SportsLeague.MENS_COLLEGE_BASKETBALL)).isFalse();
        assertThat(withKey.covers(SportsLeague.MLB)).isFalse();
        assertThat(withKey.refreshInterval()).isEqualTo(Duration.ofMinutes(3));
    }

    @Test
    void readsAnNflGamesScoreAndQuarterKeepingTheAppsNames() throws IOException {
        BigBallsLiveScores liveScores = liveScores("bb-key");
        server.expect(requestTo(startsWith(MATCHES)))
                .andExpect(queryParam("sport", "american_football"))
                .andExpect(queryParam("league", "nfl"))
                .andExpect(queryParam("date", "2026-11-02"))
                .andExpect(header("x-api-key", "bb-key"))
                .andRespond(withSuccess(fixture("matches-nfl.json"), MediaType.APPLICATION_JSON));
        ProviderGame sundayNight = new ProviderGame("2026_08_KC_LAC", SportsLeague.NFL,
                Instant.parse("2026-11-02T01:20:00Z"), false,
                new GameSide("LAC", "Los Angeles Chargers", "LAC", null), new GameSide("KC", "Kansas City Chiefs", "KC", null),
                "Example Bowl", List.of(), GameState.SCHEDULED, null);
        ProviderGame postponed = known("2026_08_LV_DEN", SportsLeague.NFL, "2026-11-02T18:00:00Z",
                "Denver Broncos", "Las Vegas Raiders");

        Map<String, ProviderGame> games = liveScores.refresh(SportsLeague.NFL, List.of(sundayNight, postponed))
                .stream().collect(Collectors.toMap(ProviderGame::id, Function.identity()));

        assertThat(games.get("2026_08_KC_LAC")).isEqualTo(new ProviderGame("2026_08_KC_LAC", SportsLeague.NFL,
                Instant.parse("2026-11-02T01:20:00Z"), false,
                new GameSide("LAC", "Los Angeles Chargers", "LAC", 17), new GameSide("KC", "Kansas City Chiefs", "KC", 10),
                // The channel comes from BigBalls when the schedule had none.
                "Example Bowl", List.of("EXTV"), GameState.LIVE, "Q3"));
        assertThat(games.get("2026_08_LV_DEN").state()).isEqualTo(GameState.POSTPONED);
        assertThat(games.get("2026_08_LV_DEN").detail()).isEqualTo("Postponed");
        assertThat(games.get("2026_08_LV_DEN").home().score()).isNull();
        server.verify();
    }

    @Test
    void findsCollegeGamesByTheirSchoolNames() throws IOException {
        BigBallsLiveScores liveScores = liveScores("bb-key");
        // Every game kicks off on 25 October in UTC, so one request covers them all.
        server.expect(requestTo(startsWith(MATCHES)))
                .andExpect(queryParam("league", "ncaaf"))
                .andExpect(queryParam("date", "2026-10-25"))
                .andRespond(withSuccess(fixture("matches-ncaaf.json"), MediaType.APPLICATION_JSON));

        Map<String, ProviderGame> games = liveScores.refresh(SportsLeague.COLLEGE_FOOTBALL, List.of(
                        known("sdsu", SportsLeague.COLLEGE_FOOTBALL, "2026-10-25T03:00:00Z", "Nevada", "San Diego State"),
                        // "Texas" and "Kansas" begin the names of two games at the same time; the closer names win.
                        known("texas", SportsLeague.COLLEGE_FOOTBALL, "2026-10-25T19:30:00Z", "Texas", "Kansas"),
                        known("hawaii", SportsLeague.COLLEGE_FOOTBALL, "2026-10-25T05:00:00Z", "Hawai'i", "UNLV"),
                        // At a neutral site the two sources can list the sides the other way round.
                        known("fresno", SportsLeague.COLLEGE_FOOTBALL, "2026-10-25T23:00:00Z",
                                "Fresno State", "San José State"),
                        // Kicking off hours apart is a different game.
                        known("utah", SportsLeague.COLLEGE_FOOTBALL, "2026-10-25T08:00:00Z", "Utah State", "Wyoming"),
                        known("unknown", SportsLeague.COLLEGE_FOOTBALL, "2026-10-25T19:00:00Z", "Montana", "Idaho")))
                .stream().collect(Collectors.toMap(ProviderGame::id, Function.identity()));

        assertThat(games).containsOnlyKeys("sdsu", "texas", "hawaii", "fresno");

        ProviderGame overtime = games.get("sdsu");
        assertThat(overtime.state()).isEqualTo(GameState.FINAL);
        assertThat(overtime.detail()).isEqualTo("Final/OT");
        assertThat(overtime.home()).isEqualTo(new GameSide("h-sdsu", "Nevada", null, 28));
        assertThat(overtime.away()).isEqualTo(new GameSide("a-sdsu", "San Diego State", null, 31));

        ProviderGame texas = games.get("texas");
        assertThat(texas.state()).isEqualTo(GameState.LIVE);
        assertThat(texas.detail()).isEqualTo("Q2");
        assertThat(texas.home().score()).isEqualTo(14);
        assertThat(texas.away().score()).isEqualTo(7);

        assertThat(games.get("hawaii").home().score()).isEqualTo(20);
        assertThat(games.get("hawaii").detail()).isEqualTo("Final");

        ProviderGame reversed = games.get("fresno");
        assertThat(reversed.home()).isEqualTo(new GameSide("h-fresno", "Fresno State", null, 21));
        assertThat(reversed.away()).isEqualTo(new GameSide("a-fresno", "San José State", null, 10));
        // Two periods of overtime scored nothing yet are still overtime.
        assertThat(reversed.detail()).isEqualTo("OT");
        server.verify();
    }

    @Test
    void twoGamesThatFitEquallyWellGiveNoScoreRatherThanAGuess() {
        BigBallsLiveScores liveScores = liveScores("bb-key");
        String side = "{\"id\": \"00000000-0000-0000-0000-0000000000d%d\", \"name\": \"%s\", \"short_name\": null}";
        String match = """
                {"id": "00000000-0000-0000-0000-00000000002%d", "sport": "american_football", "league": "NCAAF",
                 "kickoff_utc": "2026-10-25T19:00:00.000Z", "status": "live", "home": %s, "away": %s,
                 "score": {"home": %d, "away": 0}, "linescore": {"home": [7], "away": [0]}}""";
        server.expect(requestTo(startsWith(MATCHES))).andRespond(withSuccess("{\"data\": [%s, %s]}".formatted(
                match.formatted(1, side.formatted(1, "Miami Hurricanes"), side.formatted(2, "Navy Midshipmen"), 7),
                match.formatted(2, side.formatted(3, "Miami RedHawks"), side.formatted(4, "Navy Midshipmen"), 3)),
                MediaType.APPLICATION_JSON));

        assertThat(liveScores.refresh(SportsLeague.COLLEGE_FOOTBALL, List.of(
                known("miami", SportsLeague.COLLEGE_FOOTBALL, "2026-10-25T19:00:00Z", "Miami", "Navy")))).isEmpty();
    }

    @Test
    void asksOncePerKickoffDayAndNotAtAllForNothing() throws IOException {
        BigBallsLiveScores liveScores = liveScores("bb-key");
        assertThat(liveScores.refresh(SportsLeague.NFL, List.of())).isEmpty();

        server.expect(requestTo(startsWith(MATCHES))).andExpect(queryParam("date", "2026-11-02"))
                .andRespond(withSuccess(fixture("matches-nfl.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(MATCHES))).andExpect(queryParam("date", "2026-11-03"))
                .andRespond(withSuccess("{\"data\": []}", MediaType.APPLICATION_JSON));

        liveScores.refresh(SportsLeague.NFL, List.of(
                known("one", SportsLeague.NFL, "2026-11-02T01:20:00Z", "Los Angeles Chargers", "Kansas City Chiefs"),
                known("two", SportsLeague.NFL, "2026-11-02T18:00:00Z", "Denver Broncos", "Las Vegas Raiders"),
                known("three", SportsLeague.NFL, "2026-11-03T01:15:00Z", "Seattle Seahawks", "Arizona Cardinals")));
        server.verify();
    }
}
