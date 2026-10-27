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

import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Member avatars (built-in pictures, photos, characters) and the avatar packs they come from. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class AvatarIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A};

    private static String png(int size) {
        return "data:image/png;base64," + Base64.getEncoder().encodeToString(Arrays.copyOf(PNG_SIGNATURE, size));
    }

    private static String pack(String name) {
        return """
                {"version": 1, "name": "%s", "characters": [
                  {"key": "pikachu", "name": "Pikachu", "group": "Pokémon", "image": "%s"},
                  {"key": "yoda", "name": "Yoda", "group": "Star Wars", "image": "%s"}]}"""
                .formatted(name, png(300), png(400));
    }

    // ------------------------------------------------------------------ members

    @Test
    void aMemberKeepsAPhotoOrCharacterLargerThanTheOld254CharacterLimit() throws Exception {
        Registered family = register();
        String photo = png(40_000);

        mockMvc.perform(put("/api/family/members/{id}", family.memberId())
                        .header("Authorization", "Bearer " + family.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Parent\", \"color\": \"coral\", \"avatarUrl\": \"" + photo + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.avatarUrl").value(photo));

        mockMvc.perform(get("/api/family").header("Authorization", "Bearer " + family.token()))
                .andExpect(jsonPath("$.data.members[0].avatarUrl").value(photo));

        mockMvc.perform(put("/api/family/members/{id}", family.memberId())
                        .header("Authorization", "Bearer " + family.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Parent\", \"color\": \"coral\", \"avatarUrl\": \"builtin:fox\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.avatarUrl").value("builtin:fox"));
    }

    @Test
    void aMemberAvatarThatIsNotAPictureIsRefused() throws Exception {
        Registered family = register();

        mockMvc.perform(put("/api/family/members/{id}", family.memberId())
                        .header("Authorization", "Bearer " + family.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Parent\", \"color\": \"coral\", \"avatarUrl\": \"data:image/svg+xml;base64,PHN2Zy8+\"}"))
                .andExpect(status().isBadRequest());

        mockMvc.perform(post("/api/family/members")
                        .header("Authorization", "Bearer " + family.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Kid\", \"color\": \"teal\", \"avatarUrl\": \"" + png(70_000) + "\"}"))
                .andExpect(status().isBadRequest());
    }

    // -------------------------------------------------------------------- packs

    @Test
    void importListDocumentDelete_areScopedToTheFamily() throws Exception {
        Registered family = register();
        Registered other = register();

        String created = mockMvc.perform(post("/api/avatar-packs")
                        .header("Authorization", "Bearer " + family.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pack("Characters")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.name").value("Characters"))
                .andExpect(jsonPath("$.data.characterCount").value(2))
                .andExpect(jsonPath("$.data.groups", hasSize(2)))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.data.id");

        mockMvc.perform(get("/api/avatar-packs").header("Authorization", "Bearer " + family.token()))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].groups[0]").value("Pokémon"));

        mockMvc.perform(get("/api/avatar-packs/{id}", id).header("Authorization", "Bearer " + family.token()))
                .andExpect(status().isOk())
                .andExpect(content().json(pack("Characters")));

        mockMvc.perform(get("/api/avatar-packs").header("Authorization", "Bearer " + other.token()))
                .andExpect(jsonPath("$.data", hasSize(0)));
        mockMvc.perform(get("/api/avatar-packs/{id}", id).header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isNotFound());
        mockMvc.perform(delete("/api/avatar-packs/{id}", id).header("Authorization", "Bearer " + other.token()))
                .andExpect(status().isNotFound());

        mockMvc.perform(post("/api/avatar-packs")
                        .header("Authorization", "Bearer " + family.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pack("characters")))
                .andExpect(status().isConflict());

        mockMvc.perform(delete("/api/avatar-packs/{id}", id).header("Authorization", "Bearer " + family.token()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/avatar-packs").header("Authorization", "Bearer " + family.token()))
                .andExpect(jsonPath("$.data", hasSize(0)));
    }

    @Test
    void anInvalidPackIsRefusedWithTheReason() throws Exception {
        Registered family = register();
        mockMvc.perform(post("/api/avatar-packs")
                        .header("Authorization", "Bearer " + family.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"version\": 1, \"name\": \"X\", \"characters\": [{\"key\": \"yoda\", \"name\": \"Yoda\", \"image\": \"https://x/y.png\"}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("yoda")));
    }

    @Test
    void backupRoundTrip_carriesAvatarPacksAndMemberAvatars() throws Exception {
        Registered family = register();
        mockMvc.perform(post("/api/avatar-packs")
                        .header("Authorization", "Bearer " + family.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pack("Characters")))
                .andExpect(status().isCreated());
        String photo = png(30_000);
        mockMvc.perform(put("/api/family/members/{id}", family.memberId())
                        .header("Authorization", "Bearer " + family.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Parent\", \"color\": \"coral\", \"avatarUrl\": \"" + photo + "\"}"))
                .andExpect(status().isOk());

        String backup = mockMvc.perform(get("/api/backup").header("Authorization", "Bearer " + family.token()))
                .andExpect(jsonPath("$.avatarPacks", hasSize(1)))
                .andExpect(jsonPath("$.avatarPacks[0].name").value("Characters"))
                .andExpect(jsonPath("$.members[0].avatarUrl").value(photo))
                .andReturn().getResponse().getContentAsString();

        String id = JsonPath.read(mockMvc.perform(get("/api/avatar-packs").header("Authorization", "Bearer " + family.token()))
                .andReturn().getResponse().getContentAsString(), "$.data[0].id");
        mockMvc.perform(delete("/api/avatar-packs/{id}", id).header("Authorization", "Bearer " + family.token()))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/backup/restore")
                        .header("Authorization", "Bearer " + family.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(backup))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.avatarPacks").value(1));

        String listed = mockMvc.perform(get("/api/avatar-packs").header("Authorization", "Bearer " + family.token()))
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andReturn().getResponse().getContentAsString();
        String restoredId = JsonPath.read(listed, "$.data[0].id");
        mockMvc.perform(get("/api/avatar-packs/{id}", restoredId).header("Authorization", "Bearer " + family.token()))
                .andExpect(content().json(pack("Characters")));
        mockMvc.perform(get("/api/family").header("Authorization", "Bearer " + family.token()))
                .andExpect(jsonPath("$.data.members[0].avatarUrl").value(photo));

        // A backup taken before avatar packs existed still restores, with none.
        String legacy = JsonPath.parse(backup).delete("$.avatarPacks").jsonString();
        assertThat(legacy).doesNotContain("avatarPacks");
        mockMvc.perform(post("/api/backup/restore")
                        .header("Authorization", "Bearer " + family.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(legacy))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.avatarPacks").value(0));

        // A tampered backup cannot plant an avatar the API would refuse.
        String tampered = backup.replace(photo, "javascript:alert(1)");
        mockMvc.perform(post("/api/backup/restore")
                        .header("Authorization", "Bearer " + family.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tampered))
                .andExpect(status().isBadRequest());
    }

    private record Registered(String token, String memberId) {}

    private Registered register() throws Exception {
        String username = "avatar_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "password": "password123", "familyName": "Avatar Family",
                                 "members": [{"name": "Parent", "color": "CORAL"}], "timezone": "America/Los_Angeles",
                                 "starterRecipes": false}
                                """.formatted(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(body, "$.data.family.members[*].id");
        return new Registered(JsonPath.read(body, "$.data.token"), ids.getFirst());
    }
}
