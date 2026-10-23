package com.familyhub.demo.integration;

import com.familyhub.demo.config.TestcontainersConfig;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Export → change things → restore → export again: the second export must describe the first state. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class BackupIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void backupRoundTrip_restoresEveryModuleAndKeepsMembersMatchedByName() throws Exception {
        // ----- a family with something in every module -----
        String registration = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "password": "TestPassword123!", "familyName": "Backup Family",
                                 "members": [{"name": "Alice", "color": "coral"}], "timezone": "America/Los_Angeles"}
                                """.formatted("b" + System.nanoTime())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(registration, "$.data.token");
        UUID familyId = UUID.fromString(JsonPath.read(registration, "$.data.family.id"));
        UUID aliceId = UUID.fromString(JsonPath.read(registration, "$.data.family.members[0].id"));

        String bob = mockMvc.perform(post("/api/family/members")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\": \"Bob\", \"color\": \"teal\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        UUID bobId = UUID.fromString(JsonPath.read(bob, "$.data.id"));

        String board = mockMvc.perform(get("/api/chores/board").header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        String today = JsonPath.read(board, "$.data.today.periodStartDate");

        String dishes = mockMvc.perform(post("/api/chores/templates")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Dishes", "assignedToMemberIds": ["%s"], "cadence": "DAILY", "activeFrom": "%s"}
                                """.formatted(aliceId, today)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String dishesId = JsonPath.read(dishes, "$.data[0].id");
        mockMvc.perform(post("/api/chores/templates")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Feed the dog", "assignedToMemberIds": [], "cadence": "WEEKLY",
                                 "daysOfWeek": ["MONDAY", "THURSDAY"], "activeFrom": "%s"}
                                """.formatted(today)))
                .andExpect(status().isCreated());
        mockMvc.perform(put("/api/chores/templates/{id}/current-period-completion", dishesId)
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scope\": \"TODAY\", \"periodStartDate\": \"%s\"}".formatted(today)))
                .andExpect(status().isOk());

        UUID produceId = jdbcTemplate.queryForObject(
                "SELECT id FROM list_category WHERE family_id = ? AND kind = 'GROCERY' AND name = 'Produce'",
                UUID.class, familyId);
        UUID listId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO shared_list (id, family_id, name, kind, category_display_mode) VALUES (?, ?, 'Groceries', 'GROCERY', 'GROUPED')",
                listId, familyId);
        jdbcTemplate.update(
                "INSERT INTO shared_list_item (id, list_id, family_id, list_kind, category_id, text, completed) VALUES (?, ?, ?, 'GROCERY', ?, 'Apples', false)",
                UUID.randomUUID(), listId, familyId, produceId);
        jdbcTemplate.update(
                "INSERT INTO shared_list_item (id, list_id, family_id, list_kind, category_id, text, completed, completed_at) VALUES (?, ?, ?, 'GROCERY', NULL, 'Milk', true, now())",
                UUID.randomUUID(), listId, familyId);

        UUID fajitasId = jdbcTemplate.queryForObject(
                "SELECT id FROM recipe WHERE family_id = ? AND title = 'Sheet-Pan Chicken Fajitas'", UUID.class, familyId);
        UUID slotId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO meal_slot (id, family_id, week_start_date, day_index, meal_type, note, version) VALUES (?, ?, '2026-10-18', 2, 'DINNER', 'Taco night', 0)",
                slotId, familyId);
        jdbcTemplate.update(
                "INSERT INTO meal_slot_entry (id, slot_id, role, sort_order, source_type, recipe_id, title_snapshot) VALUES (?, ?, 'PRIMARY', 0, 'RECIPE', ?, 'Sheet-Pan Chicken Fajitas')",
                UUID.randomUUID(), slotId, fajitasId);

        UUID pianoId = UUID.randomUUID();
        jdbcTemplate.update(
                "INSERT INTO calendar_event (id, title, start_time, end_time, date, member_id, family_id, is_all_day, recurrence_rule, is_cancelled, source) VALUES (?, 'Piano', '16:00', '17:00', '2026-10-20', ?, ?, false, 'FREQ=WEEKLY;BYDAY=TU', false, 'NATIVE')",
                pianoId, aliceId, familyId);
        jdbcTemplate.update(
                "INSERT INTO calendar_event (id, title, start_time, end_time, date, member_id, family_id, is_all_day, recurring_event_id, original_date, is_cancelled, source) VALUES (?, 'Piano (moved)', '17:00', '18:00', '2026-10-28', ?, ?, false, ?, '2026-10-27', false, 'NATIVE')",
                UUID.randomUUID(), aliceId, familyId, pianoId);
        jdbcTemplate.update(
                "INSERT INTO calendar_event (id, title, start_time, end_time, date, member_id, family_id, is_all_day, is_cancelled, source) VALUES (?, 'Dentist', '09:00', '10:00', '2026-10-22', ?, ?, false, false, 'NATIVE')",
                UUID.randomUUID(), bobId, familyId);
        jdbcTemplate.update(
                "INSERT INTO calendar_event (id, title, start_time, end_time, date, member_id, family_id, is_all_day, is_cancelled, source, google_event_id) VALUES (?, 'Synced thing', '09:00', '10:00', '2026-10-23', ?, ?, false, false, 'GOOGLE', ?)",
                UUID.randomUUID(), aliceId, familyId, "g-" + familyId);

        // ----- export -----
        String backup = mockMvc.perform(get("/api/backup").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.family.name").value("Backup Family"))
                .andExpect(jsonPath("$.members.length()").value(2))
                .andExpect(jsonPath("$.chores.length()").value(2))
                .andExpect(jsonPath("$.chores[?(@.title == 'Feed the dog')].assignedTo").value(contains(nullValue())))
                .andExpect(jsonPath("$.chores[?(@.title == 'Feed the dog')].daysOfWeek[0]").value("MONDAY"))
                .andExpect(jsonPath("$.chores[?(@.title == 'Dishes')].completions.length()").value(1))
                .andExpect(jsonPath("$.lists.categories.length()").value(8))
                .andExpect(jsonPath("$.lists.lists.length()").value(1))
                .andExpect(jsonPath("$.lists.lists[0].items.length()").value(2))
                .andExpect(jsonPath("$.recipes.length()").value(26))
                .andExpect(jsonPath("$.meals.length()").value(1))
                .andExpect(jsonPath("$.meals[0].entries[0].titleSnapshot").value("Sheet-Pan Chicken Fajitas"))
                .andExpect(jsonPath("$.events.length()").value(3))
                .andReturn().getResponse().getContentAsString();

        // refs tie rows together inside the document
        String produceRef = JsonPath.read(backup, "$.lists.categories[?(@.name == 'Produce')].ref").toString();
        String applesCategory = JsonPath.read(backup, "$.lists.lists[0].items[?(@.text == 'Apples')].category").toString();
        assertThat(applesCategory).isEqualTo(produceRef);
        String fajitasRef = JsonPath.read(backup, "$.recipes[?(@.title == 'Sheet-Pan Chicken Fajitas')].ref").toString();
        assertThat(JsonPath.read(backup, "$.meals[0].entries[0].recipe").toString()).isEqualTo(fajitasRef.replaceAll("[\\[\\]\"]", ""));
        String pianoRef = JsonPath.read(backup, "$.events[?(@.title == 'Piano')].ref").toString();
        assertThat(JsonPath.read(backup, "$.events[?(@.title == 'Piano (moved)')].recurringEvent").toString()).isEqualTo(pianoRef);
        String aliceRefBefore = JsonPath.read(backup, "$.members[?(@.name == 'Alice')].ref").toString();

        // ----- change things: lose Bob, lose the list, add noise -----
        mockMvc.perform(delete("/api/family/members/{id}", bobId).header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());
        jdbcTemplate.update("DELETE FROM shared_list WHERE id = ?", listId);
        jdbcTemplate.update(
                "INSERT INTO shared_list (id, family_id, name, kind, category_display_mode) VALUES (?, ?, 'Noise', 'TODO', 'FLAT')",
                UUID.randomUUID(), familyId);

        // ----- restore -----
        mockMvc.perform(post("/api/backup/restore")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(backup))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.members").value(2))
                .andExpect(jsonPath("$.data.chores").value(2))
                .andExpect(jsonPath("$.data.lists").value(1))
                .andExpect(jsonPath("$.data.recipes").value(26))
                .andExpect(jsonPath("$.data.meals").value(1))
                .andExpect(jsonPath("$.data.events").value(3));

        // ----- the second export describes the first state -----
        String again = mockMvc.perform(get("/api/backup").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(2))
                .andExpect(jsonPath("$.members[?(@.name == 'Bob')].color").value("teal"))
                .andExpect(jsonPath("$.chores.length()").value(2))
                .andExpect(jsonPath("$.chores[?(@.title == 'Dishes')].completions.length()").value(1))
                .andExpect(jsonPath("$.lists.lists.length()").value(1))
                .andExpect(jsonPath("$.lists.lists[0].name").value("Groceries"))
                .andExpect(jsonPath("$.lists.lists[0].items.length()").value(2))
                .andExpect(jsonPath("$.lists.lists[0].items[?(@.text == 'Milk')].completed").value(true))
                .andExpect(jsonPath("$.recipes.length()").value(26))
                .andExpect(jsonPath("$.meals[0].entries[0].titleSnapshot").value("Sheet-Pan Chicken Fajitas"))
                .andExpect(jsonPath("$.events.length()").value(3))
                .andExpect(jsonPath("$.events[?(@.title == 'Piano (moved)')].originalDate").value("2026-10-27"))
                .andReturn().getResponse().getContentAsString();

        // Alice kept her id (matched by name), so her Google connection would survive a restore.
        assertThat(JsonPath.read(again, "$.members[?(@.name == 'Alice')].ref").toString()).isEqualTo(aliceRefBefore);
        // The restored meal entry points at the restored recipe, not a dangling id.
        String restoredFajitasRef = JsonPath.read(again, "$.recipes[?(@.title == 'Sheet-Pan Chicken Fajitas')].ref").toString().replaceAll("[\\[\\]\"]", "");
        assertThat(JsonPath.read(again, "$.meals[0].entries[0].recipe").toString()).isEqualTo(restoredFajitasRef);
        // The grocery item kept its category through the ref remap.
        List<String> categoryOfApples = JsonPath.read(again, "$.lists.lists[0].items[?(@.text == 'Apples')].category");
        List<String> produceAfter = JsonPath.read(again, "$.lists.categories[?(@.name == 'Produce')].ref");
        assertThat(categoryOfApples).containsExactly(produceAfter.get(0));
    }

    @Test
    void restore_rejectsAnUnknownMemberRef() throws Exception {
        String registration = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "password": "TestPassword123!", "familyName": "Strict Family",
                                 "members": [{"name": "Alice", "color": "coral"}]}
                                """.formatted("s" + System.nanoTime())))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String token = JsonPath.read(registration, "$.data.token");

        mockMvc.perform(post("/api/backup/restore")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"version": 1, "exportedAt": "2026-10-23T18:00:00Z",
                                 "family": {"name": "Strict Family"},
                                 "members": [{"ref": "m1", "name": "Alice", "color": "coral"}],
                                 "chores": [{"ref": "c1", "title": "Dishes", "cadence": "DAILY", "assignedTo": "ghost",
                                             "activeFrom": "2026-10-20", "completions": []}],
                                 "lists": {"showCompletedByDefault": true, "categories": [], "lists": []},
                                 "recipes": [], "meals": [], "events": []}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Chore \"Dishes\" refers to an unknown member."));

        // Nothing changed: the family still has its starter recipes and member.
        mockMvc.perform(get("/api/backup").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.members.length()").value(1))
                .andExpect(jsonPath("$.recipes.length()").value(26));
    }
}
