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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Members keep one order wherever they are listed: the order they were added in.
 * Postgres returns an unordered query's rows in storage order and an update can move
 * a row, so without a stored order editing someone could reorder the Menu and header.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class MemberOrderIntegrationTest {

    private static final String PASSWORD = "TestPassword123!";
    private static final ObjectMapper JSON = JsonMapper.builder().build();

    @Autowired
    private MockMvc mockMvc;

    private record Family(String token, List<String> ids) {}

    private Family register() throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "password": "%s", "familyName": "Order Family",
                                 "members": [{"name": "Ann", "color": "coral"},
                                             {"name": "Ben", "color": "teal"},
                                             {"name": "Cal", "color": "green"},
                                             {"name": "Dee", "color": "purple"}],
                                 "timezone": "America/Los_Angeles", "starterRecipes": false}
                                """.formatted("order_" + UUID.randomUUID().toString().substring(0, 8), PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new Family(JsonPath.read(body, "$.data.token"), JsonPath.read(body, "$.data.family.members[*].id"));
    }

    private void edit(Family f, int index, String name, String color) throws Exception {
        mockMvc.perform(put("/api/family/members/{id}", f.ids().get(index))
                        .header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"" + name + "\", \"color\": \"" + color + "\"}"))
                .andExpect(status().isOk());
    }

    private void expectOrder(Family f, String... names) throws Exception {
        mockMvc.perform(get("/api/family").header("Authorization", "Bearer " + f.token()))
                .andExpect(jsonPath("$.data.members[*].name", contains(names)));
        mockMvc.perform(get("/api/family/members").header("Authorization", "Bearer " + f.token()))
                .andExpect(jsonPath("$.data[*].name", contains(names)));
    }

    @Test
    void editingMembersDoesNotMoveThem() throws Exception {
        Family f = register();

        edit(f, 0, "Ann", "orange");
        edit(f, 2, "Cal", "#4a90d9");
        edit(f, 0, "Ann", "pink");

        expectOrder(f, "Ann", "Ben", "Cal", "Dee");
    }

    @Test
    void aNewMemberGoesLast() throws Exception {
        Family f = register();
        edit(f, 0, "Ann", "orange");

        mockMvc.perform(post("/api/family/members")
                        .header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Eve\", \"color\": \"yellow\"}"))
                .andExpect(status().isCreated());
        edit(f, 1, "Ben", "pink");

        expectOrder(f, "Ann", "Ben", "Cal", "Dee", "Eve");
    }

    @Test
    void aBackupListsMembersInOrderAndARestorePutsTheBackupsOrderBack() throws Exception {
        Family f = register();
        edit(f, 0, "Ann", "orange");

        String backup = mockMvc.perform(get("/api/backup").header("Authorization", "Bearer " + f.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members[*].name", contains("Ann", "Ben", "Cal", "Dee")))
                .andReturn().getResponse().getContentAsString();

        // The same members, listed last to first: the restore follows the backup's order.
        ObjectNode document = (ObjectNode) JSON.readTree(backup);
        ArrayNode members = (ArrayNode) document.get("members");
        List<JsonNode> reversed = new ArrayList<>();
        members.forEach(member -> reversed.addFirst(member));
        members.removeAll();
        reversed.forEach(members::add);

        mockMvc.perform(post("/api/backup/restore")
                        .header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(JSON.writeValueAsString(document)))
                .andExpect(status().isOk());

        expectOrder(f, "Dee", "Cal", "Ben", "Ann");
    }
}
