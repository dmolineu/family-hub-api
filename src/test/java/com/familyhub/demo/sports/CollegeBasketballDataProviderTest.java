package com.familyhub.demo.sports;

import com.familyhub.demo.config.SportsProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
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

/** Reading the CollegeBasketballData API, against hand-made fixtures in its shape. */
class CollegeBasketballDataProviderTest {
    private static final String BASE = "https://api.collegebasketballdata.com";

    private MockRestServiceServer server;

    private CollegeBasketballDataProvider provider(String key) {
        SportsProperties properties = new SportsProperties();
        properties.setCfbdApiKey(key);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        return new CollegeBasketballDataProvider(builder.build(), properties,
                Clock.fixed(Instant.parse("2026-10-28T19:00:00Z"), ZoneOffset.UTC));
    }

    private static String fixture(String name) throws IOException {
        return new ClassPathResource("sports/cbbd/" + name).getContentAsString(StandardCharsets.UTF_8);
    }

    private void expectTeams() throws IOException {
        // The 2026-27 season is the 2027 season to this API.
        server.expect(requestTo(startsWith(BASE + "/teams?")))
                .andExpect(queryParam("season", "2027"))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andRespond(withSuccess(fixture("teams.json"), MediaType.APPLICATION_JSON));
    }

    @Test
    void coversNothingWithoutAKey() {
        assertThat(provider("").leagues()).isEmpty();
        assertThat(provider("test-key").leagues()).containsExactly(SportsLeague.MENS_COLLEGE_BASKETBALL);
    }

    @Test
    void listsTeamsAskingWithTheFootballKey() throws IOException {
        CollegeBasketballDataProvider provider = provider("test-key");
        expectTeams();

        assertThat(provider.teams(SportsLeague.MENS_COLLEGE_BASKETBALL)).containsExactlyInAnyOrder(
                new ProviderTeam("301", "San Diego State Aztecs", "San Diego State", "SDSU",
                        "https://logos.example/cbb-301.png"),
                // No display name: the school and mascot instead.
                new ProviderTeam("302", "Gonzaga Bulldogs", "Gonzaga", "GONZ", null));
        server.verify();
    }

    @Test
    void readsATeamsGamesBetweenTwoDatesWithTheirTvChannels() throws IOException {
        CollegeBasketballDataProvider provider = provider("test-key");
        expectTeams();
        server.expect(requestTo(startsWith(BASE + "/games?")))
                .andExpect(queryParam("team", "San%20Diego%20State"))
                .andExpect(queryParam("startDateRange", "2026-10-21T00%3A00%3A00Z"))
                .andExpect(queryParam("endDateRange", "2027-01-01T00%3A00%3A00Z"))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andRespond(withSuccess(fixture("games.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(BASE + "/games/media?")))
                .andExpect(queryParam("team", "San%20Diego%20State"))
                .andExpect(queryParam("startDateRange", "2026-10-21T00%3A00%3A00Z"))
                .andExpect(queryParam("endDateRange", "2027-01-01T00%3A00%3A00Z"))
                .andRespond(withSuccess(fixture("media.json"), MediaType.APPLICATION_JSON));

        Map<String, ProviderGame> games = provider.schedule(SportsLeague.MENS_COLLEGE_BASKETBALL, "301",
                        LocalDate.of(2026, 10, 21), LocalDate.of(2026, 12, 31)).stream()
                .collect(Collectors.toMap(ProviderGame::id, Function.identity()));

        assertThat(games).containsOnlyKeys("7001", "7002", "7003", "7004");

        ProviderGame overtime = games.get("7001");
        assertThat(overtime.league()).isEqualTo(SportsLeague.MENS_COLLEGE_BASKETBALL);
        assertThat(overtime.startAt()).isEqualTo(Instant.parse("2026-11-04T03:00:00Z"));
        assertThat(overtime.home()).isEqualTo(new GameSide("301", "San Diego State", "SDSU", 80));
        assertThat(overtime.away()).isEqualTo(new GameSide("302", "Gonzaga", "GONZ", 78));
        assertThat(overtime.state()).isEqualTo(GameState.FINAL);
        assertThat(overtime.detail()).isEqualTo("Final/OT");
        assertThat(overtime.venue()).isEqualTo("Example Arena");
        // Television once each; not the radio station.
        assertThat(overtime.broadcasts()).containsExactly("EXTV");

        ProviderGame tba = games.get("7002");
        assertThat(tba.timeTbd()).isTrue();
        assertThat(tba.state()).isEqualTo(GameState.SCHEDULED);
        assertThat(tba.home()).isEqualTo(new GameSide("900", "Example State", null, null));
        assertThat(tba.detail()).isNull();

        assertThat(games.get("7003").state()).isEqualTo(GameState.POSTPONED);
        assertThat(games.get("7003").detail()).isEqualTo("Postponed");
        assertThat(games.get("7004").state()).isEqualTo(GameState.CANCELLED);
        assertThat(games.get("7004").detail()).isEqualTo("Cancelled");
        server.verify();
    }

    @Test
    void hasNoLiveScores() {
        CollegeBasketballDataProvider provider = provider("test-key");
        ProviderGame known = new ProviderGame("7001", SportsLeague.MENS_COLLEGE_BASKETBALL,
                Instant.parse("2026-11-04T03:00:00Z"), false, new GameSide("301", "San Diego State", "SDSU", null),
                new GameSide("302", "Gonzaga", "GONZ", null), null, List.of(), GameState.SCHEDULED, null);

        assertThat(provider.liveScores(SportsLeague.MENS_COLLEGE_BASKETBALL)).isFalse();
        assertThat(provider.games(SportsLeague.MENS_COLLEGE_BASKETBALL, List.of(known))).isEmpty();
        assertThat(provider.summary(SportsLeague.MENS_COLLEGE_BASKETBALL, "7001")).isEmpty();
    }
}
