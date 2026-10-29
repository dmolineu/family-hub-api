package com.familyhub.demo.integration;

import com.familyhub.demo.config.TestcontainersConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Each member's own look, and how long a screen keeps it: a member picks a built-in
 * look or one of the family's theme packs (by name, so a re-imported pack still
 * matches), or nothing for the screen's own look. Tapping them on a screen shows it
 * until the screen has gone untouched for the family's revert time.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class PersonalThemeIntegrationTest {

    private static final String PASSWORD = "TestPassword123!";

    @Autowired
    private MockMvc mockMvc;

    private record Family(String token, String momId, String mayaId) {}

    private Family register() throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "password": "%s", "familyName": "Theme Family",
                                 "members": [{"name": "Mom", "color": "coral"},
                                             {"name": "Maya", "color": "teal"}],
                                 "timezone": "America/Los_Angeles", "starterRecipes": false}
                                """.formatted("pt_" + UUID.randomUUID().toString().substring(0, 8), PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(body, "$.data.family.members[*].id");
        return new Family(JsonPath.read(body, "$.data.token"), ids.get(0), ids.get(1));
    }

    private String auth(Family f) {
        return "Bearer " + f.token();
    }

    private void importPack(Family f, String name) throws Exception {
        mockMvc.perform(post("/api/theme-packs")
                        .header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version": 1, "name": "%s", "dark": false, "tokens": {"--primary": "#b4532a"}}
                                """.formatted(name)))
                .andExpect(status().isCreated());
    }

    private org.springframework.test.web.servlet.ResultActions setTheme(Family f, String memberId, String themeJson)
            throws Exception {
        return mockMvc.perform(put("/api/family/members/{id}/theme", memberId)
                .header("Authorization", auth(f))
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"theme\": " + themeJson + "}"));
    }

    @Test
    void everyoneStartsOnTheScreensOwnLookAndNoScreenReverts() throws Exception {
        Family f = register();
        mockMvc.perform(get("/api/family").header("Authorization", auth(f)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.members[0].theme").value(nullValue()))
                .andExpect(jsonPath("$.data.themeRevertMinutes").value(0));
    }

    @Test
    void aMemberPicksABuiltInLookOrOneOfTheFamilysPacksByName() throws Exception {
        Family f = register();
        setTheme(f, f.mayaId(), "\"builtin:midnight\"")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.theme").value("builtin:midnight"));

        importPack(f, "Jeep (desert)");
        setTheme(f, f.mayaId(), "\"pack:Jeep (desert)\"")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.theme").value("pack:Jeep (desert)"));
        mockMvc.perform(get("/api/family").header("Authorization", auth(f)))
                .andExpect(jsonPath("$.data.members[1].theme").value("pack:Jeep (desert)"));

        // Nothing means the screen's own look again.
        setTheme(f, f.mayaId(), "null")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.theme").value(nullValue()));
    }

    @Test
    void aThemeMustBeALookOrAPackTheFamilyHas() throws Exception {
        Family f = register();
        setTheme(f, f.mayaId(), "\"pack:Nope\"")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("No theme pack named \"Nope\"")));
        for (String bad : new String[] {"\"rainbow\"", "\"builtin:\"", "\"builtin:Mid Night\"", "\"pack:\""}) {
            setTheme(f, f.mayaId(), bad).andExpect(status().isBadRequest());
        }
    }

    @Test
    void anotherFamilysMemberIsOffLimits() throws Exception {
        Family mine = register();
        Family theirs = register();
        setTheme(theirs, mine.mayaId(), "\"builtin:midnight\"").andExpect(status().isForbidden());
    }

    @Test
    void theRevertTimeIsFamilyWideAndNeverOrFiveFifteenOrSixtyMinutes() throws Exception {
        Family f = register();
        mockMvc.perform(put("/api/family")
                        .header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"themeRevertMinutes\": 15}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.themeRevertMinutes").value(15));

        // Changing something else leaves it alone.
        mockMvc.perform(put("/api/family")
                        .header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Renamed Family\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.themeRevertMinutes").value(15));

        for (int minutes : new int[] {7, -5, 120}) {
            mockMvc.perform(put("/api/family")
                            .header("Authorization", auth(f))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"themeRevertMinutes\": " + minutes + "}"))
                    .andExpect(status().isBadRequest());
        }
        mockMvc.perform(put("/api/family")
                        .header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"themeRevertMinutes\": 0}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.themeRevertMinutes").value(0));
    }

    @Test
    void aBackupCarriesEachMembersThemeAndTheRevertTime() throws Exception {
        Family f = register();
        setTheme(f, f.mayaId(), "\"builtin:sunrise\"").andExpect(status().isOk());
        mockMvc.perform(put("/api/family")
                        .header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"themeRevertMinutes\": 60}"))
                .andExpect(status().isOk());

        String backup = mockMvc.perform(get("/api/backup").header("Authorization", auth(f)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.family.themeRevertMinutes").value(60))
                .andExpect(jsonPath("$.members[1].theme").value("builtin:sunrise"))
                .andReturn().getResponse().getContentAsString();

        Family other = register();
        mockMvc.perform(post("/api/backup/restore")
                        .header("Authorization", auth(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(backup))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/family").header("Authorization", auth(other)))
                .andExpect(jsonPath("$.data.themeRevertMinutes").value(60))
                .andExpect(jsonPath("$.data.members[?(@.name == 'Maya')].theme").value("builtin:sunrise"));
    }
}
