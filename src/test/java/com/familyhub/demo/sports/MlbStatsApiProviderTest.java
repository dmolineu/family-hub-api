package com.familyhub.demo.sports;

import com.familyhub.demo.config.SportsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Parsing of MLB Stats API responses, against hand-made fixtures in its shape. */
class MlbStatsApiProviderTest {
    private MockRestServiceServer server;
    private MlbStatsApiProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        provider = new MlbStatsApiProvider(builder.build(), new SportsProperties());
    }

    private static String fixture(String name) throws IOException {
        return new ClassPathResource("sports/mlb/" + name).getContentAsString(StandardCharsets.UTF_8);
    }

    @Test
    void coversOnlyMlb() {
        assertThat(provider.leagues()).containsExactly(SportsLeague.MLB);
    }

    @Test
    void listsTeamsWithTheirLogoFromMlbsImageHost() throws IOException {
        server.expect(requestTo(startsWith("https://statsapi.mlb.com/api/v1/teams")))
                .andExpect(queryParam("sportId", "1"))
                .andRespond(withSuccess(fixture("teams.json"), MediaType.APPLICATION_JSON));

        List<ProviderTeam> teams = provider.teams(SportsLeague.MLB);

        assertThat(teams).hasSize(2);
        assertThat(teams.getFirst()).isEqualTo(new ProviderTeam("135", "San Diego Padres", "Padres", "SD",
                "https://www.mlbstatic.com/team-logos/135.svg"));
    }

    @Test
    void readsAScheduleWithTbdPostponedLiveAndExtraInningGames() throws IOException {
        server.expect(requestTo(startsWith("https://statsapi.mlb.com/api/v1/schedule")))
                .andExpect(queryParam("sportId", "1"))
                .andExpect(queryParam("teamId", "135"))
                .andExpect(queryParam("startDate", "2027-04-01"))
                .andExpect(queryParam("endDate", "2027-04-30"))
                // The regular season and the postseason, not spring training or exhibitions.
                .andExpect(queryParam("gameType", "R%2CF%2CD%2CL%2CW"))
                .andExpect(queryParam("hydrate", containsString("broadcasts")))
                .andRespond(withSuccess(fixture("schedule.json"), MediaType.APPLICATION_JSON));

        Map<String, ProviderGame> games = provider.schedule(SportsLeague.MLB, "135",
                        LocalDate.of(2027, 4, 1), LocalDate.of(2027, 4, 30)).stream()
                .collect(Collectors.toMap(ProviderGame::id, Function.identity()));

        assertThat(games).hasSize(5);
        ProviderGame opener = games.get("900001");
        assertThat(opener.league()).isEqualTo(SportsLeague.MLB);
        assertThat(opener.startAt()).isEqualTo(Instant.parse("2027-04-02T02:10:00Z"));
        assertThat(opener.timeTbd()).isFalse();
        assertThat(opener.home()).isEqualTo(new GameSide("135", "San Diego Padres", "SD", null));
        assertThat(opener.away()).isEqualTo(new GameSide("119", "Los Angeles Dodgers", "LAD", null));
        assertThat(opener.venue()).isEqualTo("Petco Park");
        assertThat(opener.broadcasts()).containsExactly("Padres.TV", "SportsNet LA");
        assertThat(opener.state()).isEqualTo(GameState.SCHEDULED);
        assertThat(opener.detail()).isNull();

        assertThat(games.get("900002").timeTbd()).isTrue();

        // MLB reports a postponed game as "Final" overall; the coded state tells the truth.
        assertThat(games.get("900003").state()).isEqualTo(GameState.POSTPONED);
        assertThat(games.get("900003").detail()).isEqualTo("Postponed: Rain");

        ProviderGame live = games.get("900004");
        assertThat(live.state()).isEqualTo(GameState.LIVE);
        assertThat(live.detail()).isEqualTo("Top 5th");
        assertThat(live.away().score()).isEqualTo(2);
        assertThat(live.home().score()).isEqualTo(1);

        assertThat(games.get("900005").state()).isEqualTo(GameState.FINAL);
        assertThat(games.get("900005").detail()).isEqualTo("Final/10");
    }

    @Test
    void refreshesParticularGamesInOneRequest() throws IOException {
        server.expect(requestTo(startsWith("https://statsapi.mlb.com/api/v1/schedule")))
                .andExpect(queryParam("gamePks", "900004"))
                .andRespond(withSuccess(fixture("games-by-id.json"), MediaType.APPLICATION_JSON));

        List<ProviderGame> games = provider.games(SportsLeague.MLB, List.of("900004"));

        assertThat(games).singleElement().satisfies(game -> {
            assertThat(game.state()).isEqualTo(GameState.FINAL);
            assertThat(game.detail()).isEqualTo("Final");
            assertThat(game.away().score()).isEqualTo(6);
            assertThat(game.home().score()).isEqualTo(3);
        });
    }

    @Test
    void asksForNothingWhenNoGamesNeedRefreshing() {
        assertThat(provider.games(SportsLeague.MLB, List.of())).isEmpty();
        server.verify();
    }

    @Test
    void summarisesALiveGameFromItsLinescoreAndBoxScore() throws IOException {
        server.expect(requestTo("https://statsapi.mlb.com/api/v1/game/900004/linescore"))
                .andRespond(withSuccess(fixture("linescore-live.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo("https://statsapi.mlb.com/api/v1/game/900004/boxscore"))
                .andRespond(withSuccess(fixture("boxscore.json"), MediaType.APPLICATION_JSON));

        GameSummary summary = provider.summary(SportsLeague.MLB, "900004").orElseThrow();

        assertThat(summary.gameId()).isEqualTo("900004");
        assertThat(summary.detail()).isEqualTo("Top 5th");
        assertThat(summary.lines()).containsExactly(
                new GameSummary.TeamLine("SD", List.of(
                        new GameSummary.Stat("R", "2"), new GameSummary.Stat("H", "6"), new GameSummary.Stat("E", "1"))),
                new GameSummary.TeamLine("LAD", List.of(
                        new GameSummary.Stat("R", "1"), new GameSummary.Stat("H", "4"), new GameSummary.Stat("E", "0"))));
        assertThat(summary.situation()).containsExactly(
                "2-1, 1 out", "Runners on 1st and 3rd", "At bat: Alex Batter", "Pitching: Dan Pitcher");
        assertThat(summary.leaders()).containsExactly(
                new GameSummary.Leader("Batting", "Eve Slugger", "2-3 | HR, 2 RBI"),
                new GameSummary.Leader("Pitching", "Finn Arm", "5.0 IP, ER, 7 K, BB"));
    }
}
