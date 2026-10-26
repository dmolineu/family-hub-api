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
class MemberBirthdayIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    void aBirthdayIsSavedReturnedClearedAndCarriedInTheBackup() throws Exception {
        String registration = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "password": "TestPassword123!", "familyName": "Birthday Family",
                                 "members": [{"name": "Maya", "color": "teal",
                                              "birthday": {"month": 2, "day": 29, "year": 2016}}],
                                 "starterRecipes": false}
                                """.formatted("bd_" + UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.family.members[0].birthday.month").value(2))
                .andExpect(jsonPath("$.data.family.members[0].birthday.day").value(29))
                .andExpect(jsonPath("$.data.family.members[0].birthday.year").value(2016))
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(registration, "$.data.token");
        String mayaId = JsonPath.read(registration, "$.data.family.members[0].id");

        // A parent's birthday without a year.
        String dad = mockMvc.perform(post("/api/family/members")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Dad\", \"color\": \"coral\", \"birthday\": {\"month\": 11, \"day\": 3}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.birthday.month").value(11))
                .andExpect(jsonPath("$.data.birthday.year").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        String dadId = JsonPath.read(dad, "$.data.id");

        // An impossible date is refused with a reason.
        mockMvc.perform(put("/api/family/members/{id}", dadId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Dad\", \"color\": \"coral\", \"birthday\": {\"month\": 2, \"day\": 30}}"))
                .andExpect(status().isBadRequest());

        // The backup carries birthdays, and a restore brings them back.
        String backup = mockMvc.perform(get("/api/backup").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[?(@.name == 'Maya')].birthday.year").value(2016))
                .andReturn().getResponse().getContentAsString();

        // Clearing: an update without a birthday removes it.
        mockMvc.perform(put("/api/family/members/{id}", mayaId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Maya\", \"color\": \"teal\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.birthday").doesNotExist());

        mockMvc.perform(post("/api/backup/restore")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(backup))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/family").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.data.members[?(@.name == 'Maya')].birthday.day").value(29))
                .andExpect(jsonPath("$.data.members[?(@.name == 'Dad')].birthday.month").value(11));
    }
}
