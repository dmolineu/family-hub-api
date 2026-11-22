package com.familyhub.demo.integration;

import com.familyhub.demo.dto.calendar.CalendarEventResponse;
import com.familyhub.demo.dto.calendar.CalendarWriteRequest;
import com.familyhub.demo.google.FakeGoogleCalendarGateway;
import com.familyhub.demo.google.GoogleCalendarGateway;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.repository.FamilyMemberRepository;
import com.familyhub.demo.repository.FamilyRepository;
import com.familyhub.demo.service.CalendarQueryService;
import com.familyhub.demo.service.CalendarWriteService;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The dev profile's in-memory Google (Phase 4), on the dev profile's H2 database: a family
 * that registers is connected to it at once, so the browser tests can add, show and change
 * events with no Google account. Also checks that the cache's arrays and text map on H2.
 */
@SpringBootTest(properties = {
        "spring.flyway.enabled=false",
        "spring.jpa.hibernate.ddl-auto=create-drop",
        "google.calendar.fake=true"})
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.ANY)
@AutoConfigureMockMvc
@ActiveProfiles("test")
class DevFakeGoogleIntegrationTest {

    @Autowired
    MockMvc mockMvc;

    @Autowired
    GoogleCalendarGateway gateway;

    @Autowired
    FamilyRepository families;

    @Autowired
    FamilyMemberRepository members;

    @Autowired
    CalendarWriteService writes;

    @Autowired
    CalendarQueryService query;

    @Test
    void aNewFamilyCanAddAndSeeEventsWithNoGoogleAccount() throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "dev_%s", "password": "TestPassword123!", "familyName": "Dev Family",
                                 "members": [{"name": "Maya", "color": "teal"}, {"name": "Dad", "color": "coral"}],
                                 "timezone": "America/Los_Angeles", "starterRecipes": false}
                                """.formatted(UUID.randomUUID().toString().substring(0, 8))))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        UUID familyId = UUID.fromString(JsonPath.read(body, "$.data.family.id"));
        UUID maya = UUID.fromString(JsonPath.read(body, "$.data.family.members[0].id"));

        assertThat(gateway).isInstanceOf(FakeGoogleCalendarGateway.class);
        Family family = families.findById(familyId).orElseThrow();
        assertThat(family.getDefaultGoogleCalendarId()).isNotNull();

        CalendarEventResponse created = writes.create(family, new CalendarWriteRequest(null, List.of(maya), "Swim",
                null, null, false, Instant.parse("2026-12-03T17:00:00Z"), Instant.parse("2026-12-03T18:00:00Z"),
                null, null, null, List.of("RRULE:FREQ=WEEKLY;COUNT=2"), false, null));

        assertThat(created.canEdit()).isTrue();
        assertThat(query.eventsFor(family, LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-31"), null))
                .extracting(CalendarEventResponse::title, CalendarEventResponse::memberIds)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Swim", List.of(maya)),
                        org.assertj.core.groups.Tuple.tuple("Swim", List.of(maya)));
        assertThat(members.findByFamily(family)).hasSize(2);
    }
}
