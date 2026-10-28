package com.familyhub.demo.integration;

import com.familyhub.demo.config.TestcontainersConfig;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.InboxImport;
import com.familyhub.demo.model.InboxImportKind;
import com.familyhub.demo.model.InboxImportStatus;
import com.familyhub.demo.model.InboxMessage;
import com.familyhub.demo.model.InboxOutcome;
import com.familyhub.demo.repository.FamilyRepository;
import com.familyhub.demo.repository.InboxImportRepository;
import com.familyhub.demo.repository.InboxMessageRepository;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Reviewing what the family inbox did: events waiting for a parent are approved (as
 * proposed or edited) or turned down, added ones undone, and extra senders allowed,
 * each with a parent's PIN.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class FamilyInboxReviewIntegrationTest {
    private static final String PASSWORD = "TestPassword123!";
    private static final String PIN = "2468";
    private static final String PICTURE_DAY = """
            {"title": "Picture day", "date": "2027-03-11", "endDate": "", "allDay": true, "startTime": "",
             "endTime": "", "location": "", "people": ["Maya"], "confidence": "LOW",
             "uncertainty": "The flyer gives no year"}""";
    private static final String BAKE_SALE = """
            {"title": "Bake sale", "date": "2027-03-12", "endDate": "", "allDay": false, "startTime": "",
             "endTime": "", "location": "Gym", "people": [], "confidence": "MEDIUM",
             "uncertainty": "No time given"}""";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private FamilyRepository familyRepository;

    @Autowired
    private InboxMessageRepository messageRepository;

    @Autowired
    private InboxImportRepository importRepository;

    private record Registered(String token, String username, String parentId, String kidId) {}

    @Test
    void theInboxShowsWhatWaitsAndAParentApprovesItAsProposed() throws Exception {
        Registered f = register();
        UUID waiting = pendingEvent(f, PICTURE_DAY);

        mockMvc.perform(get("/api/inbox").header("Authorization", "Bearer " + f.token()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.waiting").value(1))
                .andExpect(jsonPath("$.data.imports[0].status").value("PENDING"))
                .andExpect(jsonPath("$.data.imports[0].proposal.title").value("Picture day"))
                .andExpect(jsonPath("$.data.imports[0].proposal.uncertainty").value("The flyer gives no year"))
                .andExpect(jsonPath("$.data.imports[0].subject").value("Spring flyer"));

        mockMvc.perform(post("/api/inbox/imports/{id}/approve", waiting).header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pin(f.parentId(), "0000")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/inbox/imports/{id}/approve", waiting).header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pin(f.parentId(), PIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("ADDED"));

        assertThat(eventTitles(f, "2027-03-01", "2027-03-31")).contains("Picture day");
        // For the person the flyer named.
        assertThat(eventMember(f, "2027-03-01", "2027-03-31", "Picture day")).isEqualTo(f.kidId());
    }

    @Test
    void aParentFillsInWhatTheReaderCouldNotBeforeApproving() throws Exception {
        Registered f = register();
        UUID waiting = pendingEvent(f, BAKE_SALE);

        // No time was read, so it cannot go on as proposed...
        mockMvc.perform(post("/api/inbox/imports/{id}/approve", waiting).header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pin(f.parentId(), PIN)))
                .andExpect(status().isBadRequest());
        // ...but with the time filled in, and a different day, it can.
        mockMvc.perform(post("/api/inbox/imports/{id}/approve", waiting).header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"approverMemberId": "%s", "pin": "%s", "date": "2027-03-13",
                                 "startTime": "3:00 PM", "endTime": "5:00 PM", "allDay": false}
                                """.formatted(f.parentId(), PIN)))
                .andExpect(status().isOk());

        List<String> dates = JsonPath.read(mockMvc.perform(get("/api/calendar/events")
                        .param("startDate", "2027-03-01").param("endDate", "2027-03-31")
                        .header("Authorization", "Bearer " + f.token()))
                .andReturn().getResponse().getContentAsString(), "$.data[?(@.title == 'Bake sale')].date");
        assertThat(dates).containsExactly("2027-03-13");
    }

    @Test
    void aParentTurnsDownAProposalAndUndoesAnAddedEvent() throws Exception {
        Registered f = register();
        UUID waiting = pendingEvent(f, PICTURE_DAY);
        UUID added = approve(f, pendingEvent(f, BAKE_SALE.replace("\"startTime\": \"\"", "\"startTime\": \"9:00 AM\"")
                .replace("\"endTime\": \"\"", "\"endTime\": \"10:00 AM\"")));

        mockMvc.perform(post("/api/inbox/imports/{id}/reject", waiting).header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pin(f.parentId(), PIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("REJECTED"));
        mockMvc.perform(post("/api/inbox/imports/{id}/undo", added).header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pin(f.parentId(), PIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("UNDONE"));

        assertThat(eventTitles(f, "2027-03-01", "2027-03-31")).doesNotContain("Picture day", "Bake sale");
        mockMvc.perform(get("/api/inbox").header("Authorization", "Bearer " + f.token()))
                .andExpect(jsonPath("$.data.waiting").value(0));
    }

    @Test
    void aParentAllowsAnotherAddressAndAKidCannot() throws Exception {
        Registered f = register();

        mockMvc.perform(post("/api/inbox/senders").header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sender(f.kidId(), PIN, "grandma@example.org")))
                .andExpect(status().isForbidden());
        String body = mockMvc.perform(post("/api/inbox/senders").header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sender(f.parentId(), PIN, " Grandma@Example.org ")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.address").value("grandma@example.org"))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(body, "$.data.id");
        mockMvc.perform(post("/api/inbox/senders").header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(sender(f.parentId(), PIN, "not an address")))
                .andExpect(status().isBadRequest());

        mockMvc.perform(get("/api/inbox").header("Authorization", "Bearer " + f.token()))
                .andExpect(jsonPath("$.data.extraSenders[*].address", hasItem("grandma@example.org")))
                .andExpect(jsonPath("$.data.memberAddresses", hasItem("mom@example.com")));

        mockMvc.perform(post("/api/inbox/senders/{id}/remove", id).header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pin(f.parentId(), PIN)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/inbox").header("Authorization", "Bearer " + f.token()))
                .andExpect(jsonPath("$.data.extraSenders").isEmpty());
    }

    @Test
    void anotherFamilyCannotActOnTheFamilysImports() throws Exception {
        Registered f = register();
        Registered other = register();
        UUID waiting = pendingEvent(f, PICTURE_DAY);

        mockMvc.perform(post("/api/inbox/imports/{id}/approve", waiting)
                        .header("Authorization", "Bearer " + other.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pin(other.parentId(), PIN)))
                .andExpect(status().isNotFound());
        assertThat(importRepository.findById(waiting).orElseThrow().getStatus()).isEqualTo(InboxImportStatus.PENDING);
    }

    // ------------------------------------------------------------- helpers

    private UUID pendingEvent(Registered f, String proposal) {
        Family family = familyRepository.findByUsername(f.username()).orElseThrow();
        InboxMessage message = new InboxMessage();
        message.setFamily(family);
        message.setMessageId("<" + UUID.randomUUID() + "@example.com>");
        message.setSender("mom@example.com");
        message.setSubject("Spring flyer");
        message.setOutcome(InboxOutcome.EVENTS);
        message.setProcessedAt(Instant.now());
        messageRepository.save(message);
        InboxImport proposed = new InboxImport();
        proposed.setFamily(family);
        proposed.setMessage(message);
        proposed.setKind(InboxImportKind.EVENT);
        proposed.setStatus(InboxImportStatus.PENDING);
        proposed.setTitle(JsonPath.read(proposal, "$.title"));
        proposed.setDetails(proposal);
        proposed.setConfidence(JsonPath.read(proposal, "$.confidence"));
        proposed.setCreatedAt(Instant.now());
        return importRepository.save(proposed).getId();
    }

    private UUID approve(Registered f, UUID id) throws Exception {
        mockMvc.perform(post("/api/inbox/imports/{id}/approve", id).header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(pin(f.parentId(), PIN)))
                .andExpect(status().isOk());
        return id;
    }

    private List<String> eventTitles(Registered f, String from, String to) throws Exception {
        return JsonPath.read(mockMvc.perform(get("/api/calendar/events").param("startDate", from)
                        .param("endDate", to).header("Authorization", "Bearer " + f.token()))
                .andReturn().getResponse().getContentAsString(), "$.data[*].title");
    }

    private String eventMember(Registered f, String from, String to, String title) throws Exception {
        List<String> ids = JsonPath.read(mockMvc.perform(get("/api/calendar/events").param("startDate", from)
                        .param("endDate", to).header("Authorization", "Bearer " + f.token()))
                .andReturn().getResponse().getContentAsString(), "$.data[?(@.title == '" + title + "')].memberId");
        return ids.getFirst();
    }

    private static String pin(String approverId, String pin) {
        return "{\"approverMemberId\": \"" + approverId + "\", \"pin\": \"" + pin + "\"}";
    }

    private static String sender(String approverId, String pin, String address) {
        return "{\"approverMemberId\": \"" + approverId + "\", \"pin\": \"" + pin + "\", \"address\": \""
                + address + "\"}";
    }

    private Registered register() throws Exception {
        String username = "ir_" + UUID.randomUUID().toString().substring(0, 8);
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "password": "%s", "familyName": "Review Family",
                                 "members": [{"name": "Mom", "color": "coral", "email": "mom@example.com"},
                                             {"name": "Maya", "color": "teal"}],
                                 "timezone": "America/Los_Angeles", "starterRecipes": false}
                                """.formatted(username, PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(body, "$.data.family.members[*].id");
        Registered f = new Registered(JsonPath.read(body, "$.data.token"), username, ids.get(0), ids.get(1));
        mockMvc.perform(put("/api/family/members/{id}/role", f.kidId()).header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"KID\", \"password\": \"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/family/members/{id}/pin", f.parentId()).header("Authorization", "Bearer " + f.token())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\": \"" + PASSWORD + "\", \"pin\": \"" + PIN + "\"}"))
                .andExpect(status().isNoContent());
        return f;
    }
}
