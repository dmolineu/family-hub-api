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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Unlocking a screen (Family Settings, Preferences) with a parent's face and PIN: the
 * server checks the PIN, with the same tries and lockout as approving chores.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class ParentUnlockIntegrationTest {
    private static final String PASSWORD = "family-pass-1";

    @Autowired
    private MockMvc mockMvc;

    private record Family(String token, String momId, String dadId, String mayaId) {}

    private Family register() throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "password": "%s", "familyName": "Unlock Family",
                                 "members": [{"name": "Mom", "color": "coral"},
                                             {"name": "Dad", "color": "green"},
                                             {"name": "Maya", "color": "teal"}],
                                 "timezone": "America/Los_Angeles", "starterRecipes": false}
                                """.formatted("pu_" + UUID.randomUUID().toString().substring(0, 8), PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(body, "$.data.family.members[*].id");
        Family family = new Family(JsonPath.read(body, "$.data.token"), ids.get(0), ids.get(1), ids.get(2));
        mockMvc.perform(put("/api/family/members/{id}/pin", family.momId())
                        .header("Authorization", "Bearer " + family.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\": \"" + PASSWORD + "\", \"pin\": \"2468\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(put("/api/family/members/{id}/role", family.mayaId())
                        .header("Authorization", "Bearer " + family.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"KID\", \"password\": \"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());
        return family;
    }

    private ResultActions unlock(Family f, String memberId, String pin) throws Exception {
        return mockMvc.perform(post("/api/family/unlock")
                .header("Authorization", "Bearer " + f.token())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"approverMemberId\": \"" + memberId + "\", \"pin\": \"" + pin + "\"}"));
    }

    @Test
    void aParentsRightPinUnlocks() throws Exception {
        Family f = register();

        unlock(f, f.momId(), "2468").andExpect(status().isNoContent());
    }

    @Test
    void aWrongPinSaysHowManyTriesAreLeftAndTheFifthLocksThatParent() throws Exception {
        Family f = register();
        for (int i = 1; i <= 4; i++) {
            unlock(f, f.momId(), "9999")
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(containsString((5 - i) + " tr")));
        }
        unlock(f, f.momId(), "9999")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("locked")));

        unlock(f, f.momId(), "2468")
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("locked")));
    }

    @Test
    void aKidCannotUnlock() throws Exception {
        Family f = register();

        unlock(f, f.mayaId(), "2468").andExpect(status().isForbidden());
    }

    @Test
    void aParentWithoutAPinIsToldToSetOne() throws Exception {
        Family f = register();

        unlock(f, f.dadId(), "2468")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("hasn't set a PIN")));
    }

    @Test
    void anotherFamilysParentCannotUnlockThisOne() throws Exception {
        Family ours = register();
        Family theirs = register();

        unlock(ours, theirs.momId(), "2468").andExpect(status().is4xxClientError());
        unlock(ours, ours.momId(), "2468").andExpect(status().isNoContent());
    }

    @Test
    void aPinIsNeeded() throws Exception {
        Family f = register();

        unlock(f, f.momId(), "").andExpect(status().isBadRequest());
    }
}
