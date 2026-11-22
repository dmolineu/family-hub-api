package com.familyhub.demo.integration;

import com.familyhub.demo.config.FakeGoogleTestConfig;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The calendar API end to end (Phase 4), against the fake Google: an event created over
 * HTTP is read back from the cache, changed, one date of a series moved, and deleted; a
 * family without Google is told so with a code, not a sign-out.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import({TestcontainersConfig.class, FakeGoogleTestConfig.class})
@ActiveProfiles("test")
class CalendarApiIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    FakeGoogleTestConfig.FakeGoogleFamilies googleFamilies;

    private record Registered(String token, UUID familyId, String memberId) {
    }

    private Registered register() throws Exception {
        String body = mockMvc.perform(post("/api/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "api_%s", "password": "TestPassword123!", "familyName": "Api Family",
                                 "members": [{"name": "Maya", "color": "teal"}],
                                 "timezone": "America/Los_Angeles", "starterRecipes": false}
                                """.formatted(UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return new Registered(JsonPath.read(body, "$.data.token"),
                UUID.fromString(JsonPath.read(body, "$.data.family.id")),
                JsonPath.read(body, "$.data.family.members[0].id"));
    }

    private String december(Registered f) throws Exception {
        return mockMvc.perform(get("/api/calendar/events").param("startDate", "2026-12-01")
                        .param("endDate", "2026-12-31").header("Authorization", "Bearer " + f.token()))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    @Test
    void anEventIsCreatedReadChangedAndDeletedThroughGoogle() throws Exception {
        Registered f = register();
        googleFamilies.connect(f.familyId());

        String created = mockMvc.perform(post("/api/calendar/events").header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"memberIds": ["%s"], "title": "Swim", "allDay": false,
                                 "start": "2026-12-03T17:00:00Z", "end": "2026-12-03T18:00:00Z"}
                                """.formatted(f.memberId())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.data.id");
        String etag = JsonPath.read(created, "$.data.etag");

        assertThat(JsonPath.<List<String>>read(december(f), "$.data[*].title")).containsExactly("Swim");

        String changed = mockMvc.perform(put("/api/calendar/events/{id}", id).header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"memberIds": ["%s"], "title": "Swim lesson", "allDay": false,
                                 "start": "2026-12-03T18:00:00Z", "end": "2026-12-03T19:00:00Z", "etag": %s}
                                """.formatted(f.memberId(), toJson(etag))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<String>read(changed, "$.data.start")).isEqualTo("2026-12-03T18:00:00Z");

        // The etag read first is stale now: the change is refused with a code.
        mockMvc.perform(put("/api/calendar/events/{id}", id).header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"memberIds": ["%s"], "title": "Late", "allDay": false,
                                 "start": "2026-12-03T18:00:00Z", "end": "2026-12-03T19:00:00Z", "etag": %s}
                                """.formatted(f.memberId(), toJson(etag))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_CONFLICT"));

        mockMvc.perform(delete("/api/calendar/events/{id}", id).header("Authorization", "Bearer " + f.token()))
                .andExpect(status().isNoContent());
        assertThat(JsonPath.<List<String>>read(december(f), "$.data[*].title")).isEmpty();
    }

    @Test
    void oneDateOfASeriesMovesOnItsOwn() throws Exception {
        Registered f = register();
        googleFamilies.connect(f.familyId());
        String created = mockMvc.perform(post("/api/calendar/events").header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"memberIds": ["%s"], "title": "Class", "allDay": false,
                                 "start": "2026-12-04T00:00:00Z", "end": "2026-12-04T01:00:00Z",
                                 "timeZone": "America/Los_Angeles", "recurrence": ["RRULE:FREQ=WEEKLY;COUNT=3"]}
                                """.formatted(f.memberId())))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String series = JsonPath.read(created, "$.data.id");

        mockMvc.perform(put("/api/calendar/events/{id}/instances/{start}", series, "20261211T000000Z")
                        .header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"memberIds": ["%s"], "title": "Class (moved)", "allDay": false,
                                 "start": "2026-12-12T18:00:00Z", "end": "2026-12-12T19:00:00Z"}
                                """.formatted(f.memberId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.originalStart").value("20261211T000000Z"));

        assertThat(JsonPath.<List<String>>read(december(f), "$.data[*].title"))
                .containsExactly("Class", "Class (moved)", "Class");
    }

    @Test
    void aFamilyWithoutGoogleIsToldToConnectIt() throws Exception {
        Registered f = register();

        mockMvc.perform(post("/api/calendar/events").header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"memberIds": ["%s"], "title": "Swim", "allDay": true,
                                 "startDate": "2026-12-03", "endDate": "2026-12-04"}
                                """.formatted(f.memberId())))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GOOGLE_NOT_CONNECTED"));
        assertThat(JsonPath.<List<String>>read(december(f), "$.data[*].title")).isEmpty();
    }

    private static String toJson(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
