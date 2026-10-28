package com.familyhub.demo.sports;

import com.familyhub.demo.config.SportsProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
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
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

/** Reading nflverse's open NFL files, against hand-made fixtures in their shape. */
class NflverseProviderTest {
    private static final String GAMES_URL =
            "https://github.com/nflverse/nflverse-data/releases/download/schedules/games.csv";
    private static final String TEAMS_URL =
            "https://github.com/nflverse/nflverse-data/releases/download/teams/teams_colors_logos.csv";
    private static final MediaType CSV = MediaType.parseMediaType("text/csv");

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-28T19:00:00Z"));
    private final BigBallsLiveScores liveScores = mock(BigBallsLiveScores.class);
    private MockRestServiceServer server;
    private NflverseProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).ignoreExpectOrder(true).build();
        provider = new NflverseProvider(builder.build(), new SportsProperties(), liveScores, clock);
    }

    private static String fixture(String name) throws IOException {
        return new ClassPathResource("sports/nflverse/" + name).getContentAsString(StandardCharsets.UTF_8);
    }

    private void expectFiles(ExpectedCount count) throws IOException {
        server.expect(count, requestTo(GAMES_URL)).andRespond(withSuccess(fixture("games.csv"), CSV));
        server.expect(count, requestTo(TEAMS_URL)).andRespond(withSuccess(fixture("teams.csv"), CSV));
    }

    @Test
    void coversTheNfl() {
        assertThat(provider.leagues()).containsExactly(SportsLeague.NFL);
    }

    @Test
    void listsOnlyTodaysTeamsWithTheirNicknamesAndLogos() throws IOException {
        expectFiles(ExpectedCount.once());

        List<ProviderTeam> teams = provider.teams(SportsLeague.NFL);

        // The Oakland Raiders and San Diego Chargers are history: no game in the latest season.
        assertThat(teams).extracting(ProviderTeam::id).containsExactlyInAnyOrder("DEN", "KC", "LAC", "LV");
        assertThat(teams).filteredOn(t -> t.id().equals("LAC")).singleElement().isEqualTo(new ProviderTeam(
                "LAC", "Los Angeles Chargers", "Chargers", "LAC", "https://logos.example/lac.png"));
    }

    @Test
    void readsATeamsGamesWithKickoffsInEasternTime() throws IOException {
        expectFiles(ExpectedCount.once());

        Map<String, ProviderGame> games = provider.schedule(SportsLeague.NFL, "LAC",
                        LocalDate.of(2026, 10, 21), LocalDate.of(2026, 12, 31)).stream()
                .collect(Collectors.toMap(ProviderGame::id, Function.identity()));

        // Not last season's game, and not a game between two other teams.
        assertThat(games).containsOnlyKeys("2026_07_KC_LAC", "2026_08_LAC_DEN", "2026_09_LV_LAC", "2026_17_LAC_LV");

        ProviderGame won = games.get("2026_07_KC_LAC");
        assertThat(won.league()).isEqualTo(SportsLeague.NFL);
        assertThat(won.startAt()).isEqualTo(Instant.parse("2026-10-25T20:05:00Z"));
        assertThat(won.home()).isEqualTo(new GameSide("LAC", "Los Angeles Chargers", "LAC", 30));
        assertThat(won.away()).isEqualTo(new GameSide("KC", "Kansas City Chiefs", "KC", 27));
        assertThat(won.state()).isEqualTo(GameState.FINAL);
        assertThat(won.detail()).isEqualTo("Final/OT");
        assertThat(won.venue()).isEqualTo("Example Bowl");
        assertThat(won.broadcasts()).isEmpty();

        // 1 November 2026 is the day clocks go back: 4:25 PM is then five hours behind UTC.
        ProviderGame afterTheChange = games.get("2026_08_LAC_DEN");
        assertThat(afterTheChange.startAt()).isEqualTo(Instant.parse("2026-11-01T21:25:00Z"));
        assertThat(afterTheChange.state()).isEqualTo(GameState.SCHEDULED);
        assertThat(afterTheChange.home().score()).isNull();
        assertThat(afterTheChange.away().score()).isNull();
        assertThat(afterTheChange.detail()).isNull();
        assertThat(afterTheChange.timeTbd()).isFalse();

        // A morning game abroad, whose stadium name holds a comma.
        ProviderGame abroad = games.get("2026_09_LV_LAC");
        assertThat(abroad.startAt()).isEqualTo(Instant.parse("2026-11-08T14:30:00Z"));
        assertThat(abroad.venue()).isEqualTo("Example Stadium, Abroad");

        // No kickoff time yet: on the right day, marked TBA.
        ProviderGame unset = games.get("2026_17_LAC_LV");
        assertThat(unset.timeTbd()).isTrue();
        assertThat(unset.startAt().atZone(ZoneId.of("America/New_York")).toLocalDate())
                .isEqualTo(LocalDate.of(2026, 12, 31));
        assertThat(unset.startAt().atZone(ZoneId.of("America/Los_Angeles")).toLocalDate())
                .isEqualTo(LocalDate.of(2026, 12, 31));
    }

    @Test
    void readsTheFilesAtMostOnceAnHour() throws IOException {
        expectFiles(ExpectedCount.twice());

        provider.schedule(SportsLeague.NFL, "LAC", LocalDate.of(2026, 10, 21), LocalDate.of(2026, 12, 31));
        provider.schedule(SportsLeague.NFL, "KC", LocalDate.of(2026, 10, 21), LocalDate.of(2026, 12, 31));
        provider.teams(SportsLeague.NFL);
        clock.advance(Duration.ofMinutes(61));
        provider.schedule(SportsLeague.NFL, "LAC", LocalDate.of(2026, 10, 21), LocalDate.of(2026, 12, 31));

        server.verify();
    }

    @Test
    void hasNoLiveScoresWithoutTheLiveSource() {
        given(liveScores.covers(SportsLeague.NFL)).willReturn(false);

        assertThat(provider.liveScores(SportsLeague.NFL)).isFalse();
        assertThat(provider.games(SportsLeague.NFL, List.of(chargersGame()))).isEmpty();
        assertThat(provider.summary(SportsLeague.NFL, "2026_08_LAC_DEN")).isEmpty();
    }

    @Test
    void takesLiveScoresFromTheLiveSourceAtItsPace() {
        ProviderGame known = chargersGame();
        ProviderGame live = new ProviderGame(known.id(), SportsLeague.NFL, known.startAt(), false,
                new GameSide("DEN", "Denver Broncos", "DEN", 7), new GameSide("LAC", "Los Angeles Chargers", "LAC", 3),
                known.venue(), List.of(), GameState.LIVE, "Q2");
        given(liveScores.covers(SportsLeague.NFL)).willReturn(true);
        given(liveScores.refreshInterval()).willReturn(Duration.ofMinutes(3));
        given(liveScores.refresh(eq(SportsLeague.NFL), anyCollection())).willReturn(List.of(live));

        assertThat(provider.liveScores(SportsLeague.NFL)).isTrue();
        assertThat(provider.liveRefreshInterval(SportsLeague.NFL)).isEqualTo(Duration.ofMinutes(3));
        assertThat(provider.games(SportsLeague.NFL, List.of(known))).containsExactly(live);
    }

    private static ProviderGame chargersGame() {
        return new ProviderGame("2026_08_LAC_DEN", SportsLeague.NFL, Instant.parse("2026-11-01T21:25:00Z"), false,
                new GameSide("DEN", "Denver Broncos", "DEN", null), new GameSide("LAC", "Los Angeles Chargers", "LAC", null),
                "Example Field", List.of(), GameState.SCHEDULED, null);
    }

    /** A clock the test moves forward by hand. */
    static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
