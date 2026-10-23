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

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class ThemePackIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static final String PNG_PIXEL = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNkYPhfDwAChwGA60e6kgAAAABJRU5ErkJggg==";

    private static String pack(String name, boolean dark) {
        return """
                {"version": 1, "name": "%s", "dark": %s,
                 "tokens": {"--background": "#2f241d", "--primary": "#ffc425"},
                 "images": {"logo": "%s", "background": "%s"},
                 "slots": {"headerLogo": "logo"}}
                """.formatted(name, dark, PNG_PIXEL, PNG_PIXEL).trim();
    }

    @Test
    void importListExportDelete_areScopedToTheFamily() throws Exception {
        String token = registerAndToken();
        String otherToken = registerAndToken();

        String created = mockMvc.perform(post("/api/theme-packs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pack("Padres", true)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("Padres"))
                .andExpect(jsonPath("$.data.dark").value(true))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.data.id");

        mockMvc.perform(get("/api/theme-packs").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].id").value(id))
                // The listing carries enough to draw the pack's card without the whole file.
                .andExpect(jsonPath("$.data[0].preview.background").value("#2f241d"))
                .andExpect(jsonPath("$.data[0].preview.primary").value("#ffc425"))
                .andExpect(jsonPath("$.data[0].preview.thumbnail").value(org.hamcrest.Matchers.startsWith("data:image/jpeg;base64,")));

        // The export is the document exactly as imported.
        mockMvc.perform(get("/api/theme-packs/{id}", id).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().json(pack("Padres", true)));

        // Another family sees nothing and cannot reach it by id.
        mockMvc.perform(get("/api/theme-packs").header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(0)));
        mockMvc.perform(get("/api/theme-packs/{id}", id).header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/theme-packs/{id}", id).header("Authorization", "Bearer " + otherToken))
                .andExpect(status().isNotFound());

        // The same name twice is a conflict; a different name is fine.
        mockMvc.perform(post("/api/theme-packs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pack("padres", false)))
                .andExpect(status().isConflict());

        mockMvc.perform(delete("/api/theme-packs/{id}", id).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/theme-packs").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void invalidDocument_isRejectedWith400() throws Exception {
        String token = registerAndToken();
        mockMvc.perform(post("/api/theme-packs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 1, \"name\": \"X\", \"dark\": false, \"tokens\": {\"--primary\": \"url(http://x)\"}}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("--primary")));
    }

    @Test
    void backupRoundTrip_carriesThemePacks() throws Exception {
        String token = registerAndToken();
        mockMvc.perform(post("/api/theme-packs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pack("Padres", true)))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/api/theme-packs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pack("Pokemon", false)))
                .andExpect(status().isCreated());

        String backup = mockMvc.perform(get("/api/backup").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.themePacks", hasSize(2)))
                .andExpect(jsonPath("$.themePacks[0].name").value("Padres"))
                .andExpect(jsonPath("$.themePacks[0].dark").value(true))
                .andReturn().getResponse().getContentAsString();

        // Wipe the packs, then restore: both come back, documents byte for byte.
        List<String> ids = JsonPath.read(
                mockMvc.perform(get("/api/theme-packs").header("Authorization", "Bearer " + token))
                        .andReturn().getResponse().getContentAsString(),
                "$.data[*].id");
        for (String id : ids) {
            mockMvc.perform(delete("/api/theme-packs/{id}", id).header("Authorization", "Bearer " + token))
                    .andExpect(status().isNoContent());
        }

        mockMvc.perform(post("/api/backup/restore")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(backup))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.themePacks").value(2));

        String listed = mockMvc.perform(get("/api/theme-packs").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andReturn().getResponse().getContentAsString();
        List<String> names = JsonPath.read(listed, "$.data[*].name");
        assertThat(names).containsExactly("Padres", "Pokemon");
        String restoredId = JsonPath.read(listed, "$.data[0].id");
        mockMvc.perform(get("/api/theme-packs/{id}", restoredId).header("Authorization", "Bearer " + token))
                .andExpect(content().json(pack("Padres", true)));

        // A backup taken before theme packs existed (no field at all) still restores.
        String legacy = backup.replaceFirst(",\\s*\"themePacks\"\\s*:\\s*\\[.*?\\]\\s*(?=[,}])", "");
        assertThat(legacy).doesNotContain("themePacks");
        mockMvc.perform(post("/api/backup/restore")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(legacy))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.themePacks").value(0));
        mockMvc.perform(get("/api/theme-packs").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    private String registerAndToken() throws Exception {
        String username = "theme_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "password": "password123", "familyName": "Theme Family",
                                 "members": [{"name": "Parent", "color": "CORAL"}], "timezone": "America/Los_Angeles",
                                 "starterRecipes": false}
                                """.formatted(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.token");
    }
}
