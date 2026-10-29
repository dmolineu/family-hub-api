package com.familyhub.demo.integration;

import com.familyhub.demo.config.TestcontainersConfig;
import com.jayway.jsonpath.JsonPath;
import com.familyhub.demo.model.ChoreCompletionStatus;
import com.familyhub.demo.model.ChorePeriodCompletion;
import com.familyhub.demo.repository.ChorePeriodCompletionRepository;
import com.familyhub.demo.repository.ChoreTemplateRepository;
import com.familyhub.demo.repository.FamilyMemberRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Kids' chores wait for a parent: a parent picks themselves, enters their own PIN and
 * approves (points granted) or rejects (chore unticked); everything lands in the log.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class ChoreApprovalIntegrationTest {

    private static final String PASSWORD = "TestPassword123!";

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ChoreTemplateRepository choreTemplateRepository;

    @Autowired
    private ChorePeriodCompletionRepository chorePeriodCompletionRepository;

    @Autowired
    private FamilyMemberRepository familyMemberRepository;

    private record Family(String token, String momId, String dadId, String mayaId) {}

    private Family register() throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "password": "%s", "familyName": "Chore Family",
                                 "members": [{"name": "Mom", "color": "coral"},
                                             {"name": "Dad", "color": "green"},
                                             {"name": "Maya", "color": "teal"}],
                                 "timezone": "America/Los_Angeles", "starterRecipes": false}
                                """.formatted("ca_" + UUID.randomUUID().toString().substring(0, 8), PASSWORD)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.family.members[0].role").value("PARENT"))
                .andExpect(jsonPath("$.data.family.members[0].hasPin").value(false))
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(body, "$.data.family.members[*].id");
        return new Family(JsonPath.read(body, "$.data.token"), ids.get(0), ids.get(1), ids.get(2));
    }

    private String auth(Family f) {
        return "Bearer " + f.token();
    }

    private void makeKid(Family f, String memberId) throws Exception {
        mockMvc.perform(put("/api/family/members/{id}/role", memberId)
                        .header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"KID\", \"password\": \"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("KID"));
    }

    private void setPin(Family f, String memberId, String pin) throws Exception {
        mockMvc.perform(put("/api/family/members/{id}/pin", memberId)
                        .header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\": \"" + PASSWORD + "\", \"pin\": \"" + pin + "\"}"))
                .andExpect(status().isNoContent());
    }

    private String createChore(Family f, String title, String memberId, int points) throws Exception {
        String body = mockMvc.perform(post("/api/chores/templates")
                        .header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "%s", "assignedToMemberIds": ["%s"], "cadence": "DAILY",
                                 "activeFrom": "2026-01-01", "points": %d}
                                """.formatted(title, memberId, points)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data[0].points").value(points))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data[0].id");
    }

    private String today(Family f) throws Exception {
        String board = mockMvc.perform(get("/api/chores/board").header("Authorization", auth(f)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(board, "$.data.today.periodStartDate");
    }

    private static String unlock(String parentId, String pin) {
        return "{\"approverMemberId\": \"" + parentId + "\", \"pin\": \"" + pin + "\"}";
    }

    private String periodBody(String today, String extra) {
        return "{\"scope\": \"TODAY\", \"periodStartDate\": \"" + today + "\"" + extra + "}";
    }

    private int points(Family f, String memberId) throws Exception {
        String body = mockMvc.perform(get("/api/chores/points").header("Authorization", auth(f)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<Integer> values = JsonPath.read(body, "$.data[?(@.memberId == '" + memberId + "')].points");
        return values.getFirst();
    }

    @Test
    void aKidsChoreWaitsForAParentAndTheLogShowsWhoApprovedIt() throws Exception {
        Family f = register();
        makeKid(f, f.mayaId());
        setPin(f, f.momId(), "2468");
        mockMvc.perform(get("/api/family").header("Authorization", auth(f)))
                .andExpect(jsonPath("$.data.members[?(@.name == 'Mom')].hasPin").value(true));

        // Kids have no PIN, and a wrong family password changes nothing.
        mockMvc.perform(put("/api/family/members/{id}/pin", f.mayaId())
                        .header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\": \"" + PASSWORD + "\", \"pin\": \"1111\"}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/api/family/members/{id}/role", f.dadId())
                        .header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"KID\", \"password\": \"nope\"}"))
                .andExpect(status().isForbidden());

        String choreId = createChore(f, "Feed the cat", f.mayaId(), 5);
        String today = today(f);

        mockMvc.perform(put("/api/chores/templates/{id}/current-period-completion", choreId)
                        .header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(periodBody(today, "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item.completed").value(true))
                .andExpect(jsonPath("$.data.item.status").value("PENDING"))
                .andExpect(jsonPath("$.data.item.points").value(5));
        assertThat(points(f, f.mayaId())).isZero();

        mockMvc.perform(get("/api/chores/approvals/count").header("Authorization", auth(f)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.count").value(1));
        String pending = mockMvc.perform(post("/api/chores/approvals/pending")
                        .header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unlock(f.momId(), "2468")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andExpect(jsonPath("$.data[0].completedAt").isNotEmpty())
                .andExpect(jsonPath("$.data[0].choreTitle").value("Feed the cat"))
                .andExpect(jsonPath("$.data[0].memberName").value("Maya"))
                .andExpect(jsonPath("$.data[0].points").value(5))
                .andReturn().getResponse().getContentAsString();
        String completionId = JsonPath.read(pending, "$.data[0].completionId");

        // A wrong PIN is refused with the tries left.
        mockMvc.perform(post("/api/chores/approvals")
                        .header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approverMemberId\": \"" + f.momId() + "\", \"pin\": \"0000\", \"approve\": [\""
                                + completionId + "\"]}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("4 tries left")));

        mockMvc.perform(post("/api/chores/approvals")
                        .header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approverMemberId\": \"" + f.momId() + "\", \"pin\": \"2468\", \"approve\": [\""
                                + completionId + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approved").value(1))
                .andExpect(jsonPath("$.data.rejected").value(0));
        assertThat(points(f, f.mayaId())).isEqualTo(5);
        mockMvc.perform(get("/api/chores/approvals/count").header("Authorization", auth(f)))
                .andExpect(jsonPath("$.data.count").value(0));

        // The log needs a parent's PIN and names who did what, newest first.
        mockMvc.perform(post("/api/chores/activity")
                        .header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approverMemberId\": \"" + f.momId() + "\", \"pin\": \"2468\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].kind").value("APPROVED"))
                .andExpect(jsonPath("$.data[0].memberName").value("Maya"))
                .andExpect(jsonPath("$.data[0].actorName").value("Mom"))
                .andExpect(jsonPath("$.data[0].points").value(5))
                .andExpect(jsonPath("$.data[0].occurredAt").isNotEmpty())
                .andExpect(jsonPath("$.data[1].kind").value("COMPLETED"))
                .andExpect(jsonPath("$.data[1].choreTitle").value("Feed the cat"));

        // Unticking an approved kid's chore needs a parent's PIN and takes the points back.
        mockMvc.perform(delete("/api/chores/templates/{id}/current-period-completion", choreId)
                        .header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(periodBody(today, "")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("PIN")));
        mockMvc.perform(delete("/api/chores/templates/{id}/current-period-completion", choreId)
                        .header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(periodBody(today, ", \"approverMemberId\": \"" + f.momId() + "\", \"pin\": \"2468\"")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item.completed").value(false));
        assertThat(points(f, f.mayaId())).isZero();
        mockMvc.perform(post("/api/chores/activity")
                        .header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approverMemberId\": \"" + f.momId() + "\", \"pin\": \"2468\", \"memberId\": \""
                                + f.mayaId() + "\"}"))
                .andExpect(jsonPath("$.data[0].kind").value("REVERSED"))
                .andExpect(jsonPath("$.data[0].points").value(-5));

        // The backup carries roles, points and the log; a restore keeps the balance.
        String backup = mockMvc.perform(get("/api/backup").header("Authorization", auth(f)))
                .andExpect(jsonPath("$.members[?(@.name == 'Maya')].role").value("KID"))
                .andExpect(jsonPath("$.chores[0].points").value(5))
                .andExpect(jsonPath("$.activity", hasSize(3)))
                .andReturn().getResponse().getContentAsString();
        assertThat(backup).doesNotContain("pinHash").doesNotContain("$2a$");
        mockMvc.perform(post("/api/backup/restore")
                        .header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(backup))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/chores/activity")
                        .header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approverMemberId\": \"" + f.momId() + "\", \"pin\": \"2468\"}"))
                .andExpect(jsonPath("$.data", hasSize(3)));
    }

    @Test
    void aRejectedChoreIsUntickedAndCanBeDoneAgain() throws Exception {
        Family f = register();
        makeKid(f, f.mayaId());
        setPin(f, f.dadId(), "1357");
        String choreId = createChore(f, "Make the bed", f.mayaId(), 2);
        String today = today(f);
        mockMvc.perform(put("/api/chores/templates/{id}/current-period-completion", choreId)
                        .header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(periodBody(today, "")))
                .andExpect(status().isOk());
        String completionId = JsonPath.read(
                mockMvc.perform(post("/api/chores/approvals/pending")
                                .header("Authorization", auth(f))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(unlock(f.dadId(), "1357")))
                        .andReturn().getResponse().getContentAsString(),
                "$.data[0].completionId");

        mockMvc.perform(post("/api/chores/approvals")
                        .header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approverMemberId\": \"" + f.dadId() + "\", \"pin\": \"1357\", \"reject\": [\""
                                + completionId + "\"], \"reason\": \"Still messy\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rejected").value(1));
        mockMvc.perform(get("/api/chores/board").header("Authorization", auth(f)))
                .andExpect(jsonPath("$.data.today.assignees[0].chores[0].completed").value(false));
        mockMvc.perform(post("/api/chores/activity")
                        .header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approverMemberId\": \"" + f.dadId() + "\", \"pin\": \"1357\"}"))
                .andExpect(jsonPath("$.data[0].kind").value("REJECTED"))
                .andExpect(jsonPath("$.data[0].reason").value("Still messy"))
                .andExpect(jsonPath("$.data[0].actorName").value("Dad"));

        mockMvc.perform(put("/api/chores/templates/{id}/current-period-completion", choreId)
                        .header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(periodBody(today, "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item.status").value("PENDING"));
    }

    @Test
    void fiveWrongPinsLockOnlyThatParent() throws Exception {
        Family f = register();
        setPin(f, f.momId(), "2468");
        setPin(f, f.dadId(), "1357");
        String log = "/api/chores/activity";
        for (int i = 1; i <= 4; i++) {
            mockMvc.perform(post(log).header("Authorization", auth(f)).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"approverMemberId\": \"" + f.momId() + "\", \"pin\": \"9999\"}"))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.message").value(containsString((5 - i) + " tr")));
        }
        mockMvc.perform(post(log).header("Authorization", auth(f)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approverMemberId\": \"" + f.momId() + "\", \"pin\": \"9999\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("locked")));
        // Even the right PIN is refused while locked; Dad is unaffected.
        mockMvc.perform(post(log).header("Authorization", auth(f)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approverMemberId\": \"" + f.momId() + "\", \"pin\": \"2468\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("locked")));
        mockMvc.perform(post(log).header("Authorization", auth(f)).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approverMemberId\": \"" + f.dadId() + "\", \"pin\": \"1357\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void aParentsOwnChoreIsApprovedAtOnce() throws Exception {
        Family f = register();
        String choreId = createChore(f, "Take out recycling", f.dadId(), 3);
        String today = today(f);
        mockMvc.perform(put("/api/chores/templates/{id}/current-period-completion", choreId)
                        .header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(periodBody(today, "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item.status").value("APPROVED"));
        assertThat(points(f, f.dadId())).isEqualTo(3);
        mockMvc.perform(get("/api/chores/approvals/count").header("Authorization", auth(f)))
                .andExpect(jsonPath("$.data.count").value(0));
    }

    @Test
    void aPinIsExactlyFourDigits() throws Exception {
        Family f = register();
        // The pad checks a PIN the moment its fourth digit is in, so a longer one
        // could never be entered: it would be sent short and count as a wrong try.
        for (String pin : new String[] {"135", "13579", "135790", "13a7"}) {
            mockMvc.perform(put("/api/family/members/{id}/pin", f.dadId())
                            .header("Authorization", auth(f))
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"password\": \"" + PASSWORD + "\", \"pin\": \"" + pin + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.errors[0].message").value("A PIN is 4 digits"));
        }
        setPin(f, f.dadId(), "1357");
    }

    @Test
    void theLastParentCannotBecomeAKid() throws Exception {
        Family f = register();
        makeKid(f, f.mayaId());
        makeKid(f, f.dadId());
        mockMvc.perform(put("/api/family/members/{id}/role", f.momId())
                        .header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"KID\", \"password\": \"" + PASSWORD + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value(containsString("at least one parent")));
    }

    @Test
    void seeingWhatWaitsNeedsAParentsPinAndEachDayWaitsSeparately() throws Exception {
        Family f = register();
        makeKid(f, f.mayaId());
        setPin(f, f.momId(), "2468");
        String choreId = createChore(f, "Feed the cat", f.mayaId(), 5);
        String today = today(f);
        mockMvc.perform(put("/api/chores/templates/{id}/current-period-completion", choreId)
                        .header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(periodBody(today, "")))
                .andExpect(status().isOk());

        // Yesterday's tick, never approved, still waits next to today's.
        LocalDate yesterday = LocalDate.parse(today).minusDays(1);
        ChorePeriodCompletion earlier = new ChorePeriodCompletion();
        earlier.setChoreTemplate(choreTemplateRepository.findById(UUID.fromString(choreId)).orElseThrow());
        earlier.setPeriodStartDate(yesterday);
        earlier.setPeriodEndDate(yesterday);
        earlier.setCompletedAt(yesterday.atTime(17, 30));
        earlier.setCompletedByMember(familyMemberRepository.findById(UUID.fromString(f.mayaId())).orElseThrow());
        earlier.setStatus(ChoreCompletionStatus.PENDING);
        chorePeriodCompletionRepository.save(earlier);

        // The count needs no PIN (it drives the "N to approve" button)...
        mockMvc.perform(get("/api/chores/approvals/count").header("Authorization", auth(f)))
                .andExpect(jsonPath("$.data.count").value(2));
        // ...but what waits, and for whom, needs a parent's PIN.
        mockMvc.perform(post("/api/chores/approvals/pending")
                        .header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unlock(f.momId(), "1111")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/chores/approvals/pending")
                        .header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unlock(f.mayaId(), "1111")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value(containsString("Only a parent")));

        String pending = mockMvc.perform(post("/api/chores/approvals/pending")
                        .header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unlock(f.momId(), "2468")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(2)))
                .andExpect(jsonPath("$.data[0].choreTitle").value("Feed the cat"))
                .andExpect(jsonPath("$.data[0].periodStartDate").value(yesterday.toString()))
                .andExpect(jsonPath("$.data[0].completedAt").value(containsString(yesterday + "T17:30")))
                .andExpect(jsonPath("$.data[1].choreTitle").value("Feed the cat"))
                .andExpect(jsonPath("$.data[1].periodStartDate").value(today))
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(pending, "$.data[*].completionId");

        // Kids cannot open the chore log either.
        mockMvc.perform(post("/api/chores/activity")
                        .header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unlock(f.mayaId(), "1111")))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/chores/approvals")
                        .header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approverMemberId\": \"" + f.momId() + "\", \"pin\": \"2468\", \"approve\": [\""
                                + ids.get(0) + "\", \"" + ids.get(1) + "\"]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approved").value(2));
        assertThat(points(f, f.mayaId())).isEqualTo(10);
    }
}
