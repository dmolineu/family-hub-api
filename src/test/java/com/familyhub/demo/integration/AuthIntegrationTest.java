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

import java.util.UUID;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class AuthIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    private String uniqueUsername() {
        // Not System.nanoTime(): it counts from boot, and on a Mac awake for weeks its 16 digits
        // push the username past the 20-character limit.
        return "user" + UUID.randomUUID().toString().substring(0, 8);
    }

    private String registerJson(String username) {
        return """
                {
                    "username": "%s",
                    "password": "password123",
                    "familyName": "Integration Family",
                    "members": [
                        { "name": "Mom", "color": "coral", "email": "mom@test.com" }
                    ]
                }
                """.formatted(username);
    }

    @Test
    void fullRegisterLoginFlow() throws Exception {
        String username = uniqueUsername();

        // Register
        String registerBody = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.family.name").value("Integration Family"))
                .andExpect(jsonPath("$.data.family.members[0].name").value("Mom"))
                .andReturn().getResponse().getContentAsString();

        String registerToken = JsonPath.read(registerBody, "$.data.token");

        // Login
        String loginBody = mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                { "username": "%s", "password": "password123" }
                                """.formatted(username)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andReturn().getResponse().getContentAsString();

        String loginToken = JsonPath.read(loginBody, "$.data.token");

        // Use login token to access protected endpoint
        mockMvc.perform(get("/api/family")
                        .header("Authorization", "Bearer " + loginToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("Integration Family"))
                .andExpect(jsonPath("$.data.members[0].name").value("Mom"));
    }

    @Test
    void register_starterRecipesOptOut_leavesTheLibraryEmpty() throws Exception {
        String username = uniqueUsername();
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {
                                    "username": "%s",
                                    "password": "password123",
                                    "familyName": "Empty Library Family",
                                    "members": [{ "name": "Mom", "color": "coral" }],
                                    "starterRecipes": false
                                }
                                """.formatted(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.data.token");

        mockMvc.perform(get("/api/recipes").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isEmpty());
    }

    @Test
    void register_byDefault_seedsTheStarterRecipes() throws Exception {
        String username = uniqueUsername();
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(body, "$.data.token");

        mockMvc.perform(get("/api/recipes").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(26));
    }

    @Test
    void register_invalidData_returns400() throws Exception {
        String payload = """
                {
                    "username": "ab",
                    "password": "short",
                    "familyName": "",
                    "members": []
                }
                """;

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors").isNotEmpty());
    }

    @Test
    void register_invalidTimezone_returns400() throws Exception {
        String payload = """
                {
                    "username": "%s",
                    "password": "password123",
                    "familyName": "Integration Family",
                    "members": [
                        { "name": "Mom", "color": "coral", "email": "mom@test.com" }
                    ],
                    "timezone": "Mars/Olympus"
                }
                """.formatted(uniqueUsername());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Timezone must be a valid IANA timezone."));
    }

    @Test
    void protectedEndpoint_noToken_returns401() throws Exception {
        mockMvc.perform(get("/api/family"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.httpStatus").value(401));
    }

    @Test
    void protectedEndpoint_malformedToken_returns401() throws Exception {
        mockMvc.perform(get("/api/family")
                        .header("Authorization", "Bearer not-a-valid-jwt"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.httpStatus").value(401));
    }

    @Test
    void register_duplicateUsername_returns409() throws Exception {
        String username = uniqueUsername();

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(username)))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(username)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Username " + username + " already exists."));
    }

    private String registerAndGetToken(String username) throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(registerJson(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.token");
    }

    private org.springframework.test.web.servlet.ResultActions login(String username, String password) throws Exception {
        return mockMvc.perform(post("/api/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "username": "%s", "password": "%s" }
                        """.formatted(username, password)));
    }

    private org.springframework.test.web.servlet.ResultActions changePassword(
            String token, String currentPassword, String newPassword) throws Exception {
        var request = put("/api/auth/password")
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                        { "currentPassword": "%s", "newPassword": "%s" }
                        """.formatted(currentPassword, newPassword));
        if (token != null) {
            request.header("Authorization", "Bearer " + token);
        }
        return mockMvc.perform(request);
    }

    @Test
    void changePassword_signsOutEveryOtherDevice_andKeepsThisOne() throws Exception {
        String username = uniqueUsername();
        String wallToken = registerAndGetToken(username);
        String phoneToken = JsonPath.read(login(username, "password123")
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(), "$.data.token");

        String body = changePassword(phoneToken, "password123", "a-new-password")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.family.name").value("Integration Family"))
                .andReturn().getResponse().getContentAsString();
        String freshToken = JsonPath.read(body, "$.data.token");

        mockMvc.perform(get("/api/family").header("Authorization", "Bearer " + wallToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/family").header("Authorization", "Bearer " + phoneToken))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/family").header("Authorization", "Bearer " + freshToken))
                .andExpect(status().isOk());
        login(username, "password123").andExpect(status().isUnauthorized());
        login(username, "a-new-password").andExpect(status().isOk());
    }

    @Test
    void changePassword_wrongCurrentPassword_isRefusedAndStaysSignedIn() throws Exception {
        String username = uniqueUsername();
        String token = registerAndGetToken(username);

        changePassword(token, "not-the-password", "a-new-password")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("That isn't the password this family signs in with."));

        mockMvc.perform(get("/api/family").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        login(username, "password123").andExpect(status().isOk());
    }

    @Test
    void changePassword_withoutSigningIn_returns401() throws Exception {
        changePassword(null, "password123", "a-new-password")
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changePassword_tooShort_returns400() throws Exception {
        String token = registerAndGetToken(uniqueUsername());

        changePassword(token, "password123", "short")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].message").value("Password must be between 8 - 100 characters"));
    }
}
