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
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.mock;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.startsWith;

/** Reading the CollegeFootballData API, against hand-made fixtures in its shape. */
class CollegeFootballDataProviderTest {
    private static final String BASE = "https://api.collegefootballdata.com";

    private final BigBallsLiveScores liveScores = mock(BigBallsLiveScores.class);
    private MockRestServiceServer server;

    private CollegeFootballDataProvider provider(String key, String today) {
        SportsProperties properties = new SportsProperties();
        properties.setCfbdApiKey(key);
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        return new CollegeFootballDataProvider(builder.build(), properties, liveScores,
                Clock.fixed(Instant.parse(today + "T19:00:00Z"), ZoneOffset.UTC));
    }

    private static String fixture(String name) throws IOException {
        return new ClassPathResource("sports/cfbd/" + name).getContentAsString(StandardCharsets.UTF_8);
    }

    private void expectTeams(String year) throws IOException {
        server.expect(requestTo(startsWith(BASE + "/teams?")))
                .andExpect(queryParam("year", year))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andRespond(withSuccess(fixture("teams.json"), MediaType.APPLICATION_JSON));
    }

    @Test
    void coversNothingWithoutAKey() {
        assertThat(provider("", "2026-10-28").leagues()).isEmpty();
        assertThat(provider("test-key", "2026-10-28").leagues()).containsExactly(SportsLeague.COLLEGE_FOOTBALL);
    }

    @Test
    void listsFbsAndFcsTeamsAskingWithTheKey() throws IOException {
        CollegeFootballDataProvider provider = provider("test-key", "2026-10-28");
        expectTeams("2026");

        List<ProviderTeam> teams = provider.teams(SportsLeague.COLLEGE_FOOTBALL);

        // Not Division II, nor a programme with no division at all.
        assertThat(teams).extracting(ProviderTeam::id).containsExactlyInAnyOrder("21", "2440", "301");
        assertThat(teams).filteredOn(t -> t.id().equals("21")).singleElement().isEqualTo(new ProviderTeam(
                "21", "San Diego State Aztecs", "San Diego State", "SDSU", "https://logos.example/21.png"));
        assertThat(teams).filteredOn(t -> t.id().equals("301")).singleElement().isEqualTo(new ProviderTeam(
                "301", "San Diego Toreros", "San Diego", "USD", null));
        server.verify();
    }

    @Test
    void readsATeamsSeasonWithItsTvChannels() throws IOException {
        CollegeFootballDataProvider provider = provider("test-key", "2026-10-28");
        expectTeams("2026");
        server.expect(requestTo(startsWith(BASE + "/games?")))
                .andExpect(queryParam("year", "2026"))
                .andExpect(queryParam("team", "San%20Diego%20State"))
                .andExpect(queryParam("seasonType", "both"))
                .andExpect(header("Authorization", "Bearer test-key"))
                .andRespond(withSuccess(fixture("games.json"), MediaType.APPLICATION_JSON));
        server.expect(requestTo(startsWith(BASE + "/games/media?")))
                .andExpect(queryParam("year", "2026"))
                .andExpect(queryParam("team", "San%20Diego%20State"))
                .andExpect(queryParam("seasonType", "both"))
                .andExpect(queryParam("mediaType", "tv"))
                .andRespond(withSuccess(fixture("media.json"), MediaType.APPLICATION_JSON));

        Map<String, ProviderGame> games = provider.schedule(SportsLeague.COLLEGE_FOOTBALL, "21",
                        LocalDate.of(2026, 10, 21), LocalDate.of(2027, 10, 28)).stream()
                .collect(Collectors.toMap(ProviderGame::id, Function.identity()));

        // The game of 18 October is before the window.
        assertThat(games).containsOnlyKeys("401002", "401003", "401004");

        ProviderGame overtime = games.get("401002");
        assertThat(overtime.league()).isEqualTo(SportsLeague.COLLEGE_FOOTBALL);
        assertThat(overtime.startAt()).isEqualTo(Instant.parse("2026-10-25T03:00:00Z"));
        assertThat(overtime.home()).isEqualTo(new GameSide("2440", "Nevada", "NEV", 28));
        assertThat(overtime.away()).isEqualTo(new GameSide("21", "San Diego State", "SDSU", 31));
        assertThat(overtime.state()).isEqualTo(GameState.FINAL);
        assertThat(overtime.detail()).isEqualTo("Final/OT");
        assertThat(overtime.venue()).isEqualTo("Example Field");
        assertThat(overtime.broadcasts()).containsExactly("Example Sports Network");

        ProviderGame tba = games.get("401003");
        assertThat(tba.timeTbd()).isTrue();
        assertThat(tba.state()).isEqualTo(GameState.SCHEDULED);
        assertThat(tba.detail()).isNull();
        assertThat(tba.home().score()).isNull();
        assertThat(tba.broadcasts()).containsExactly("EXTV");

        // A bowl game against a team outside the list: no abbreviation, no channel yet.
        ProviderGame bowl = games.get("401004");
        assertThat(bowl.home()).isEqualTo(new GameSide("62", "Hawai'i", null, null));
        assertThat(bowl.broadcasts()).isEmpty();
        server.verify();
    }

    @Test
    void beforeASeasonStartsReadsTheComingOneToo() throws IOException {
        CollegeFootballDataProvider provider = provider("test-key", "2027-06-15");
        expectTeams("2026");
        for (String year : List.of("2026", "2027")) {
            server.expect(requestTo(startsWith(BASE + "/games?"))).andExpect(queryParam("year", year))
                    .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
            server.expect(requestTo(startsWith(BASE + "/games/media?"))).andExpect(queryParam("year", year))
                    .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        }

        assertThat(provider.schedule(SportsLeague.COLLEGE_FOOTBALL, "21",
                LocalDate.of(2027, 6, 8), LocalDate.of(2028, 6, 15))).isEmpty();
        server.verify();
    }

    @Test
    void takesLiveScoresFromTheLiveSourceOnlyWhenItCoversCollegeFootball() {
        CollegeFootballDataProvider provider = provider("test-key", "2026-10-28");
        ProviderGame known = new ProviderGame("401003", SportsLeague.COLLEGE_FOOTBALL,
                Instant.parse("2026-11-01T03:00:00Z"), false, new GameSide("21", "San Diego State", "SDSU", null),
                new GameSide("301", "San Diego", "USD", null), null, List.of(), GameState.SCHEDULED, null);

        given(liveScores.covers(SportsLeague.COLLEGE_FOOTBALL)).willReturn(false);
        assertThat(provider.liveScores(SportsLeague.COLLEGE_FOOTBALL)).isFalse();
        assertThat(provider.games(SportsLeague.COLLEGE_FOOTBALL, List.of(known))).isEmpty();

        given(liveScores.covers(SportsLeague.COLLEGE_FOOTBALL)).willReturn(true);
        given(liveScores.refreshInterval()).willReturn(Duration.ofMinutes(3));
        given(liveScores.refresh(eq(SportsLeague.COLLEGE_FOOTBALL), anyCollection())).willReturn(List.of(known));
        assertThat(provider.liveScores(SportsLeague.COLLEGE_FOOTBALL)).isTrue();
        assertThat(provider.liveRefreshInterval(SportsLeague.COLLEGE_FOOTBALL)).isEqualTo(Duration.ofMinutes(3));
        assertThat(provider.games(SportsLeague.COLLEGE_FOOTBALL, List.of(known))).containsExactly(known);
        assertThat(provider.summary(SportsLeague.COLLEGE_FOOTBALL, "401003")).isEmpty();
    }
}
