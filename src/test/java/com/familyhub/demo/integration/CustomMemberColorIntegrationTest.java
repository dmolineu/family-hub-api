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
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A member's colour can be any #rrggbb, not only the seven named colours: some
 * characters' pictures look wrong on every named colour. It is saved, read back,
 * carried by backups, and anything that is not a colour is refused.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class CustomMemberColorIntegrationTest {

    private static final String PASSWORD = "TestPassword123!";

    @Autowired
    private MockMvc mockMvc;

    private record Family(String token, String momId, String mayaId) {}

    private Family register(String mayaColor) throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "password": "%s", "familyName": "Colour Family",
                                 "members": [{"name": "Mom", "color": "coral"},
                                             {"name": "Maya", "color": "%s"}],
                                 "timezone": "America/Los_Angeles", "starterRecipes": false}
                                """.formatted("cc_" + UUID.randomUUID().toString().substring(0, 8), PASSWORD,
                                mayaColor)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(body, "$.data.family.members[*].id");
        return new Family(JsonPath.read(body, "$.data.token"), ids.get(0), ids.get(1));
    }

    private ResultActions setColor(Family f, String color) throws Exception {
        return mockMvc.perform(put("/api/family/members/{id}", f.mayaId())
                .header("Authorization", "Bearer " + f.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"name\": \"Maya\", \"color\": \"" + color + "\"}"));
    }

    private ResultActions family(Family f) throws Exception {
        return mockMvc.perform(get("/api/family").header("Authorization", "Bearer " + f.token()));
    }

    @Test
    void aMemberCanPickAnyColour() throws Exception {
        Family f = register("teal");

        setColor(f, "#4A90D9")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.color").value("#4a90d9"));

        family(f).andExpect(jsonPath("$.data.members[*].color", hasItem("#4a90d9")));
    }

    @Test
    void theNamedColoursStillWork() throws Exception {
        Family f = register("#123abc");
        family(f).andExpect(jsonPath("$.data.members[*].color", hasItem("#123abc")));

        setColor(f, "purple")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.color").value("purple"));
    }

    @Test
    void anythingThatIsNotAColourIsRefused() throws Exception {
        Family f = register("teal");
        for (String color : List.of("#4a90d", "blue", "#4a90dz", "4a90d9")) {
            setColor(f, color).andExpect(status().isBadRequest());
        }
        family(f).andExpect(jsonPath("$.data.members[*].color", hasItem("teal")));
    }

    @Test
    void aCustomColourSurvivesBackupAndRestore() throws Exception {
        Family f = register("teal");
        setColor(f, "#4a90d9").andExpect(status().isOk());
        String backup = mockMvc.perform(get("/api/backup").header("Authorization", "Bearer " + f.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[*].color", hasItem("#4a90d9")))
                .andReturn().getResponse().getContentAsString();

        setColor(f, "orange").andExpect(status().isOk());
        mockMvc.perform(post("/api/backup/restore")
                        .header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(backup))
                .andExpect(status().isOk());

        family(f).andExpect(jsonPath("$.data.members[*].color", hasItem("#4a90d9")));
    }
}
