package com.familyhub.demo.integration;

import com.familyhub.demo.config.TestcontainersConfig;
import com.familyhub.demo.sports.GameSide;
import com.familyhub.demo.sports.GameState;
import com.familyhub.demo.sports.GameSummary;
import com.familyhub.demo.sports.MlbStatsApiProvider;
import com.familyhub.demo.sports.ProviderGame;
import com.familyhub.demo.sports.ProviderTeam;
import com.familyhub.demo.sports.SportsLeague;
import com.familyhub.demo.scheduler.SportsLivePoller;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Following teams, their games on the calendar, live scores and the panel, with MLB's source faked. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class SportsIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private SportsLivePoller livePoller;

    @MockitoBean
    private MlbStatsApiProvider mlb;

    private static final ProviderTeam PADRES = new ProviderTeam("135", "San Diego Padres", "Padres", "SD",
            "https://img.example/135.svg");
    private static final ProviderTeam DODGERS = new ProviderTeam("119", "Los Angeles Dodgers", "Dodgers", "LAD",
            "https://img.example/119.svg");

    private final Instant now = Instant.now().truncatedTo(ChronoUnit.SECONDS);
    private final Instant tomorrow = now.plus(1, ChronoUnit.DAYS);

    private ProviderGame game(String id, Instant start, GameState state, Integer away, Integer home, String detail) {
        return new ProviderGame(id, SportsLeague.MLB, start, false,
                new GameSide("135", "San Diego Padres", "SD", home),
                new GameSide("119", "Los Angeles Dodgers", "LAD", away),
                "Petco Park", List.of("Padres.TV"), state, detail);
    }

    @BeforeEach
    void provider() {
        reset(mlb);
        given(mlb.leagues()).willReturn(Set.of(SportsLeague.MLB));
        given(mlb.teams(SportsLeague.MLB)).willReturn(List.of(PADRES, DODGERS));
        given(mlb.schedule(eq(SportsLeague.MLB), eq("135"), any(), any())).willReturn(List.of(
                game("g-tomorrow", tomorrow, GameState.SCHEDULED, null, null, null),
                game("g-live", now.minus(1, ChronoUnit.HOURS), GameState.LIVE, 1, 2, "Top 5th")));
    }

    @Test
    void listsLeaguesAndSaysWhichHaveADataSource() throws Exception {
        String token = register().token();
        mockMvc.perform(get("/api/sports/leagues").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(4)))
                .andExpect(jsonPath("$.data[?(@.league == 'MLB')].available").value(true))
                .andExpect(jsonPath("$.data[?(@.league == 'NFL')].available").value(false))
                .andExpect(jsonPath("$.data[?(@.league == 'NFL')].name").value("NFL"));
    }

    @Test
    void searchesALeaguesTeams() throws Exception {
        String token = register().token();
        mockMvc.perform(get("/api/sports/leagues/MLB/teams").param("query", "pad")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value("135"))
                .andExpect(jsonPath("$.data[0].shortName").value("Padres"));
        mockMvc.perform(get("/api/sports/leagues/NFL/teams").header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void followingATeamPutsItsGamesInTheFansLanes() throws Exception {
        Registered f = register();
        String teamId = follow(f, "135");

        mockMvc.perform(patch("/api/sports/teams/{id}", teamId).header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberIds\": [\"" + f.memberId() + "\"], \"color\": \"#2f241d\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.memberIds[0]").value(f.memberId()))
                .andExpect(jsonPath("$.data.color").value("#2f241d"));

        mockMvc.perform(get("/api/sports/games").header("Authorization", "Bearer " + f.token())
                        .param("from", now.minus(2, ChronoUnit.DAYS).toString().substring(0, 10))
                        .param("to", now.plus(3, ChronoUnit.DAYS).toString().substring(0, 10)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[?(@.providerGameId == 'g-tomorrow')].home.name").value("San Diego Padres"))
                .andExpect(jsonPath("$.data[?(@.providerGameId == 'g-tomorrow')].memberIds[0]").value(f.memberId()))
                .andExpect(jsonPath("$.data[?(@.providerGameId == 'g-tomorrow')].color").value("#2f241d"))
                .andExpect(jsonPath("$.data[?(@.providerGameId == 'g-tomorrow')].broadcasts[0]").value("Padres.TV"))
                .andExpect(jsonPath("$.data[?(@.providerGameId == 'g-live')].state").value("LIVE"))
                .andExpect(jsonPath("$.data[?(@.providerGameId == 'g-live')].detail").value("Top 5th"));

        // "Show on calendar" off: the games leave the calendar but the team stays followed.
        mockMvc.perform(patch("/api/sports/teams/{id}", teamId).header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"showOnCalendar\": false, \"hideScores\": true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.showOnCalendar").value(false))
                .andExpect(jsonPath("$.data.hideScores").value(true));
        mockMvc.perform(get("/api/sports/games").header("Authorization", "Bearer " + f.token())
                        .param("from", now.minus(2, ChronoUnit.DAYS).toString().substring(0, 10))
                        .param("to", now.plus(3, ChronoUnit.DAYS).toString().substring(0, 10)))
                .andExpect(jsonPath("$.data", hasSize(0)));

        // Unfollowing removes the team and its games.
        mockMvc.perform(delete("/api/sports/teams/{id}", teamId).header("Authorization", "Bearer " + f.token()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/sports/teams").header("Authorization", "Bearer " + f.token()))
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void followingTwiceOrAnUnknownTeamIsRefused() throws Exception {
        Registered f = register();
        follow(f, "135");
        mockMvc.perform(post("/api/sports/teams").header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"league\": \"MLB\", \"providerTeamId\": \"135\"}"))
                .andExpect(status().isConflict());
        mockMvc.perform(post("/api/sports/teams").header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"league\": \"MLB\", \"providerTeamId\": \"999\"}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/sports/teams").header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"league\": \"NFL\", \"providerTeamId\": \"24\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anotherFamilySeesNoneOfIt() throws Exception {
        Registered f = register();
        Registered other = register();
        String teamId = follow(f, "135");

        mockMvc.perform(get("/api/sports/teams").header("Authorization", "Bearer " + other.token()))
                .andExpect(jsonPath("$.data", hasSize(0)));
        mockMvc.perform(get("/api/sports/live").header("Authorization", "Bearer " + other.token()))
                .andExpect(jsonPath("$.data", hasSize(0)));
        mockMvc.perform(delete("/api/sports/teams/{id}", teamId).header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isNotFound());
        mockMvc.perform(patch("/api/sports/teams/{id}", teamId).header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberIds\": [\"" + other.memberId() + "\"]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void livePollingRefreshesOnlyGamesInTheirWindow() throws Exception {
        Registered f = register();
        follow(f, "135");
        mockMvc.perform(get("/api/sports/live").header("Authorization", "Bearer " + f.token()))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].providerGameId").value("g-live"))
                .andExpect(jsonPath("$.data[0].away.score").value(1));

        given(mlb.games(eq(SportsLeague.MLB), anyCollection())).willReturn(List.of(
                game("g-live", now.minus(1, ChronoUnit.HOURS), GameState.LIVE, 3, 2, "Bottom 7th")));
        livePoller.refresh();

        verify(mlb).games(eq(SportsLeague.MLB), eq(List.of("g-live")));
        mockMvc.perform(get("/api/sports/live").header("Authorization", "Bearer " + f.token()))
                .andExpect(jsonPath("$.data[0].away.score").value(3))
                .andExpect(jsonPath("$.data[0].detail").value("Bottom 7th"));

        // Once final, the game leaves the live list and is no longer polled.
        given(mlb.games(eq(SportsLeague.MLB), anyCollection())).willReturn(List.of(
                game("g-live", now.minus(1, ChronoUnit.HOURS), GameState.FINAL, 3, 4, "Final")));
        livePoller.refresh();
        mockMvc.perform(get("/api/sports/live").header("Authorization", "Bearer " + f.token()))
                .andExpect(jsonPath("$.data", hasSize(0)));
        reset(mlb);
        given(mlb.leagues()).willReturn(Set.of(SportsLeague.MLB));
        livePoller.refresh();
        verify(mlb, never()).games(any(), anyCollection());
    }

    @Test
    void thePanelReadsTheGamesSummary() throws Exception {
        Registered f = register();
        follow(f, "135");
        String gameId = JsonPath.read(mockMvc.perform(get("/api/sports/live")
                        .header("Authorization", "Bearer " + f.token()))
                .andReturn().getResponse().getContentAsString(), "$.data[0].id");
        given(mlb.summary(SportsLeague.MLB, "g-live")).willReturn(Optional.of(new GameSummary("g-live", "Top 5th",
                List.of(new GameSummary.TeamLine("LAD", List.of(new GameSummary.Stat("R", "1")))),
                List.of("2-1, 1 out"),
                List.of(new GameSummary.Leader("Batting", "Eve Slugger", "2-3")))));

        mockMvc.perform(get("/api/sports/games/{id}/summary", gameId).header("Authorization", "Bearer " + f.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.detail").value("Top 5th"))
                .andExpect(jsonPath("$.data.situation[0]").value("2-1, 1 out"))
                .andExpect(jsonPath("$.data.leaders[0].name").value("Eve Slugger"));
    }

    @Test
    void backupCarriesFollowedTeamsAndTheirFans() throws Exception {
        Registered f = register();
        String teamId = follow(f, "135");
        mockMvc.perform(patch("/api/sports/teams/{id}", teamId).header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberIds\": [\"" + f.memberId() + "\"], \"hideScores\": true}"))
                .andExpect(status().isOk());

        String backup = mockMvc.perform(get("/api/backup").header("Authorization", "Bearer " + f.token()))
                .andExpect(jsonPath("$.followedTeams", hasSize(1)))
                .andExpect(jsonPath("$.followedTeams[0].providerTeamId").value("135"))
                .andExpect(jsonPath("$.followedTeams[0].hideScores").value(true))
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(post("/api/backup/restore").header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(backup))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.followedTeams").value(1));
        String listed = mockMvc.perform(get("/api/sports/teams").header("Authorization", "Bearer " + f.token()))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].hideScores").value(true))
                .andExpect(jsonPath("$.data[0].memberIds", hasSize(1)))
                .andReturn().getResponse().getContentAsString();
        // A restored team's games come back with the next sync.
        livePoller.syncUnsyncedTeams();
        mockMvc.perform(get("/api/sports/games").header("Authorization", "Bearer " + f.token())
                        .param("from", now.minus(2, ChronoUnit.DAYS).toString().substring(0, 10))
                        .param("to", now.plus(3, ChronoUnit.DAYS).toString().substring(0, 10)))
                .andExpect(jsonPath("$.data", hasSize(2)));
        JsonPath.read(listed, "$.data[0].id");
    }

    // ------------------------------------------------------------- helpers

    private record Registered(String token, String memberId) {}

    private Registered register() throws Exception {
        String username = "sports_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "password": "password123", "familyName": "Sports Family",
                                 "members": [{"name": "Dad", "color": "CORAL"}], "timezone": "America/Los_Angeles",
                                 "starterRecipes": false}
                                """.formatted(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(body, "$.data.family.members[*].id");
        return new Registered(JsonPath.read(body, "$.data.token"), ids.getFirst());
    }

    private String follow(Registered f, String providerTeamId) throws Exception {
        String body = mockMvc.perform(post("/api/sports/teams").header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"league\": \"MLB\", \"providerTeamId\": \"" + providerTeamId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("San Diego Padres"))
                .andExpect(jsonPath("$.data.logoUrl").value("https://img.example/135.svg"))
                .andExpect(jsonPath("$.data.showOnCalendar").value(true))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.id");
    }
}
