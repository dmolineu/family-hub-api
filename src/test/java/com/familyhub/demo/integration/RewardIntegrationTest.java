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

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The rewards shop: parents keep a catalogue, kids spend chore points, a parent's PIN decides. */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class RewardIntegrationTest {

    private static final String PASSWORD = "TestPassword123!";
    private static final String MOM_PIN = "2468";

    @Autowired
    private MockMvc mockMvc;

    // ------------------------------------------------------------- catalogue

    @Test
    void parentsKeepTheCatalogueWithTheirOwnPin() throws Exception {
        Family f = familyWithAKid();

        mockMvc.perform(post("/api/rewards").header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reward(f.momId(), "0000", "Screen time", 20)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/rewards").header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reward(f.mayaId(), MOM_PIN, "Screen time", 20)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/rewards").header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reward(f.momId(), MOM_PIN, "Screen time", 0)))
                .andExpect(status().isBadRequest());

        String id = createReward(f, "Screen time", 20);
        mockMvc.perform(put("/api/rewards/{id}", id).header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reward(f.momId(), MOM_PIN, "30 min screen time", 25)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.title").value("30 min screen time"))
                .andExpect(jsonPath("$.data.points").value(25));

        // Anyone can look at the catalogue; it needs no PIN.
        mockMvc.perform(get("/api/rewards").header("Authorization", auth(f)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rewards", hasSize(1)))
                .andExpect(jsonPath("$.data.rewards[0].title").value("30 min screen time"));

        mockMvc.perform(post("/api/rewards/{id}/archive", id).header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unlock(f.momId(), MOM_PIN)))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/rewards").header("Authorization", auth(f)))
                .andExpect(jsonPath("$.data.rewards", hasSize(0)));
    }

    @Test
    void managingTheCatalogueOpensWithAParentsPin() throws Exception {
        Family f = familyWithAKid();
        createReward(f, "Screen time", 20);

        mockMvc.perform(post("/api/rewards/manage").header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unlock(f.momId(), "0000")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/rewards/manage").header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unlock(f.mayaId(), MOM_PIN)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/rewards/manage").header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unlock(f.momId(), MOM_PIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rewards", hasSize(1)))
                .andExpect(jsonPath("$.data.rewards[0].title").value("Screen time"));
    }

    @Test
    void anotherFamilyCannotSeeOrChangeTheCatalogue() throws Exception {
        Family f = familyWithAKid();
        Family other = familyWithAKid();
        String id = createReward(f, "Ice cream", 10);

        mockMvc.perform(get("/api/rewards").header("Authorization", auth(other)))
                .andExpect(jsonPath("$.data.rewards", hasSize(0)));
        mockMvc.perform(put("/api/rewards/{id}", id).header("Authorization", auth(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reward(other.momId(), MOM_PIN, "Mine now", 1)))
                .andExpect(status().isNotFound());
        mockMvc.perform(post("/api/rewards/{id}/redemptions", id).header("Authorization", auth(other))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\": \"" + other.mayaId() + "\"}"))
                .andExpect(status().isNotFound());
    }

    // ------------------------------------------------------------ spending

    @Test
    void aKidAsksForARewardAndAParentsApprovalSpendsThePoints() throws Exception {
        Family f = familyWithAKid();
        earn(f, 30);
        String rewardId = createReward(f, "Screen time", 20);

        String redemptionId = ask(f, rewardId, f.mayaId());

        mockMvc.perform(get("/api/rewards").header("Authorization", auth(f)))
                .andExpect(jsonPath("$.data.waiting", hasSize(1)))
                .andExpect(jsonPath("$.data.waiting[0].id").value(redemptionId))
                .andExpect(jsonPath("$.data.waiting[0].memberName").value("Maya"))
                .andExpect(jsonPath("$.data.waiting[0].rewardTitle").value("Screen time"))
                .andExpect(jsonPath("$.data.waiting[0].points").value(20));
        mockMvc.perform(get("/api/chores/approvals/count").header("Authorization", auth(f)))
                .andExpect(jsonPath("$.data.rewardRequests").value(1));
        // Asking holds the points but spends nothing yet.
        assertThat(points(f, f.mayaId())).isEqualTo(30);

        mockMvc.perform(post("/api/rewards/redemptions/decide").header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decide(f.momId(), MOM_PIN, List.of(redemptionId), List.of(), null)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.approved").value(1))
                .andExpect(jsonPath("$.data.declined").value(0));

        assertThat(points(f, f.mayaId())).isEqualTo(10);
        mockMvc.perform(get("/api/rewards").header("Authorization", auth(f)))
                .andExpect(jsonPath("$.data.waiting", hasSize(0)));
        mockMvc.perform(post("/api/chores/activity").header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unlock(f.momId(), MOM_PIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].kind").value("REDEEMED"))
                .andExpect(jsonPath("$.data[0].choreTitle").value("Screen time"))
                .andExpect(jsonPath("$.data[0].points").value(-20))
                .andExpect(jsonPath("$.data[0].memberName").value("Maya"))
                .andExpect(jsonPath("$.data[0].actorName").value("Mom"));
    }

    @Test
    void askingNeedsEnoughPointsCountingRequestsStillWaiting() throws Exception {
        Family f = familyWithAKid();
        earn(f, 30);
        String rewardId = createReward(f, "Screen time", 20);
        ask(f, rewardId, f.mayaId());

        mockMvc.perform(post("/api/rewards/{id}/redemptions", rewardId).header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\": \"" + f.mayaId() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("10 points")));
    }

    @Test
    void aDeclinedRequestFreesThePointsAndTheLogSaysWhy() throws Exception {
        Family f = familyWithAKid();
        earn(f, 30);
        String rewardId = createReward(f, "Screen time", 20);
        String redemptionId = ask(f, rewardId, f.mayaId());

        mockMvc.perform(post("/api/rewards/redemptions/decide").header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decide(f.momId(), MOM_PIN, List.of(), List.of(redemptionId), "Homework first")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.declined").value(1));

        assertThat(points(f, f.mayaId())).isEqualTo(30);
        mockMvc.perform(post("/api/chores/activity").header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unlock(f.momId(), MOM_PIN)))
                .andExpect(jsonPath("$.data[0].kind").value("DECLINED"))
                .andExpect(jsonPath("$.data[0].points").value(0))
                .andExpect(jsonPath("$.data[0].reason").value("Homework first"));
        // The points are free again.
        ask(f, rewardId, f.mayaId());
    }

    @Test
    void onlyTheKidWhoAskedCanTakeTheirRequestBack() throws Exception {
        Family f = familyWithAKid();
        earn(f, 30);
        String rewardId = createReward(f, "Screen time", 20);
        String redemptionId = ask(f, rewardId, f.mayaId());

        mockMvc.perform(post("/api/rewards/redemptions/{id}/cancel", redemptionId).header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\": \"" + f.momId() + "\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/rewards/redemptions/{id}/cancel", redemptionId).header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\": \"" + f.mayaId() + "\"}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/api/rewards").header("Authorization", auth(f)))
                .andExpect(jsonPath("$.data.waiting", hasSize(0)));
    }

    @Test
    void approvalRefusesWhenThePointsAreNoLongerThere() throws Exception {
        Family f = familyWithAKid();
        String choreId = earn(f, 30);
        String rewardId = createReward(f, "Screen time", 20);
        String redemptionId = ask(f, rewardId, f.mayaId());
        // A parent unticks the approved chore, taking the 30 points back.
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                        .delete("/api/chores/templates/{id}/current-period-completion", choreId)
                        .header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(periodBody(today(f), ", \"approverMemberId\": \"" + f.momId() + "\", \"pin\": \"" + MOM_PIN + "\"")))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/rewards/redemptions/decide").header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(decide(f.momId(), MOM_PIN, List.of(redemptionId), List.of(), null)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value(containsString("Maya")));
        assertThat(points(f, f.mayaId())).isZero();
    }

    @Test
    void backupCarriesTheCatalogue() throws Exception {
        Family f = familyWithAKid();
        createReward(f, "Screen time", 20);
        String archived = createReward(f, "Old reward", 5);
        mockMvc.perform(post("/api/rewards/{id}/archive", archived).header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unlock(f.momId(), MOM_PIN)))
                .andExpect(status().isNoContent());

        String backup = mockMvc.perform(get("/api/backup").header("Authorization", auth(f)))
                .andExpect(jsonPath("$.rewards", hasSize(1)))
                .andExpect(jsonPath("$.rewards[0].title").value("Screen time"))
                .andExpect(jsonPath("$.rewards[0].points").value(20))
                .andReturn().getResponse().getContentAsString();

        mockMvc.perform(post("/api/backup/restore").header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(backup))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rewards").value(1));
        mockMvc.perform(get("/api/rewards").header("Authorization", auth(f)))
                .andExpect(jsonPath("$.data.rewards", hasSize(1)))
                .andExpect(jsonPath("$.data.rewards[0].title").value("Screen time"));

        String legacy = JsonPath.parse(backup).delete("$.rewards").jsonString();
        mockMvc.perform(post("/api/backup/restore").header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(legacy))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rewards").value(0));
    }

    // ------------------------------------------------------------- helpers

    private record Family(String token, String momId, String mayaId) {}

    /** Mom (a parent with a PIN) and Maya (a kid). */
    private Family familyWithAKid() throws Exception {
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "password": "%s", "familyName": "Reward Family",
                                 "members": [{"name": "Mom", "color": "coral"}, {"name": "Maya", "color": "teal"}],
                                 "timezone": "America/Los_Angeles", "starterRecipes": false}
                                """.formatted("rw_" + UUID.randomUUID().toString().substring(0, 8), PASSWORD)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        List<String> ids = JsonPath.read(body, "$.data.family.members[*].id");
        Family f = new Family(JsonPath.read(body, "$.data.token"), ids.get(0), ids.get(1));
        mockMvc.perform(put("/api/family/members/{id}/role", f.mayaId()).header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\": \"KID\", \"password\": \"" + PASSWORD + "\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/family/members/{id}/pin", f.momId()).header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\": \"" + PASSWORD + "\", \"pin\": \"" + MOM_PIN + "\"}"))
                .andExpect(status().isNoContent());
        return f;
    }

    /** Maya ticks a chore worth the points and Mom approves it; returns the chore's id. */
    private String earn(Family f, int points) throws Exception {
        String chore = mockMvc.perform(post("/api/chores/templates").header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title": "Tidy room", "assignedToMemberIds": ["%s"], "cadence": "DAILY",
                                 "activeFrom": "2026-01-01", "points": %d}
                                """.formatted(f.mayaId(), Math.min(points, 100))))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String choreId = JsonPath.read(chore, "$.data[0].id");
        mockMvc.perform(put("/api/chores/templates/{id}/current-period-completion", choreId)
                        .header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(periodBody(today(f), "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.item.status").value("PENDING"));
        String pending = mockMvc.perform(post("/api/chores/approvals/pending").header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(unlock(f.momId(), MOM_PIN)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data", hasSize(1)))
                .andReturn().getResponse().getContentAsString();
        String completionId = JsonPath.read(pending, "$.data[0].completionId");
        mockMvc.perform(post("/api/chores/approvals").header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approverMemberId\": \"" + f.momId() + "\", \"pin\": \"" + MOM_PIN
                                + "\", \"approve\": [\"" + completionId + "\"]}"))
                .andExpect(status().isOk());
        assertThat(points(f, f.mayaId())).isEqualTo(points);
        return choreId;
    }

    private String createReward(Family f, String title, int points) throws Exception {
        String body = mockMvc.perform(post("/api/rewards").header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(reward(f.momId(), MOM_PIN, title, points)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.title").value(title))
                .andExpect(jsonPath("$.data.points").value(points))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.id");
    }

    private String ask(Family f, String rewardId, String memberId) throws Exception {
        String body = mockMvc.perform(post("/api/rewards/{id}/redemptions", rewardId).header("Authorization", auth(f))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"memberId\": \"" + memberId + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.id");
    }

    private static String reward(String approverId, String pin, String title, int points) {
        return """
                {"approverMemberId": "%s", "pin": "%s", "title": "%s", "description": "Any day after homework",
                 "points": %d}""".formatted(approverId, pin, title, points);
    }

    private static String decide(String approverId, String pin, List<String> approve, List<String> decline,
                                 String reason) {
        return """
                {"approverMemberId": "%s", "pin": "%s", "approve": %s, "decline": %s, "reason": %s}"""
                .formatted(approverId, pin, json(approve), json(decline), reason == null ? "null" : "\"" + reason + "\"");
    }

    private static String json(List<String> ids) {
        return ids.stream().map(id -> "\"" + id + "\"").toList().toString();
    }

    private static String unlock(String parentId, String pin) {
        return "{\"approverMemberId\": \"" + parentId + "\", \"pin\": \"" + pin + "\"}";
    }

    private String auth(Family f) {
        return "Bearer " + f.token();
    }

    private String today(Family f) throws Exception {
        String board = mockMvc.perform(get("/api/chores/board").header("Authorization", auth(f)))
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(board, "$.data.today.periodStartDate");
    }

    private static String periodBody(String today, String extra) {
        return "{\"scope\": \"TODAY\", \"periodStartDate\": \"" + today + "\"" + extra + "}";
    }

    private int points(Family f, String memberId) throws Exception {
        String body = mockMvc.perform(get("/api/chores/points").header("Authorization", auth(f)))
                .andReturn().getResponse().getContentAsString();
        List<Integer> values = JsonPath.read(body, "$.data[?(@.memberId == '" + memberId + "')].points");
        return values.getFirst();
    }
}
