package com.familyhub.demo.google;

import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.GoogleCalendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Whose lanes a Google event goes in. An event the hub wrote names its members in a
 * shared extended property, which wins. Otherwise it belongs to the calendar's lane
 * members. Either way, family members invited by email who have not declined are added.
 * The tag's order is kept (its first member leads); others follow in the family's order.
 * Ids that are not this family's members are ignored, so a member deleted later, or a
 * tag edited by hand in Google, never breaks a read.
 */
class MemberAttributionTest {

    private final FamilyMember dad = member("Dad", "dad@example.com");
    private final FamilyMember mom = member("Mom", "Mom@Example.com");
    private final FamilyMember maya = member("Maya", null);
    private final List<FamilyMember> family = List.of(dad, mom, maya);
    private final GoogleCalendar hub = new GoogleCalendar();

    private final MemberAttribution attribution = new MemberAttribution();

    private static FamilyMember member(String name, String email) {
        FamilyMember member = new FamilyMember();
        member.setId(UUID.randomUUID());
        member.setName(name);
        member.setEmail(email);
        return member;
    }

    private static Event tagged(String members) {
        return new Event().setExtendedProperties(new Event.ExtendedProperties()
                .setShared(Map.of(MemberAttribution.MEMBERS_PROPERTY, members)));
    }

    @Test
    void theHubsMemberTagWinsOverTheCalendarsLanes() {
        hub.getLaneMembers().add(dad);

        // In the tag's order: the first one leads and gives the event its colour.
        assertThat(attribution.membersOf(tagged(maya.getId() + "," + mom.getId()), hub, family))
                .containsExactly(maya.getId(), mom.getId());
    }

    @Test
    void invitedMembersComeAfterTheTaggedOnesInTheFamilysOrder() {
        Event invite = tagged(maya.getId().toString())
                .setAttendees(List.of(new EventAttendee().setEmail("mom@example.com"),
                        new EventAttendee().setEmail("dad@example.com")));

        assertThat(attribution.membersOf(invite, hub, family))
                .containsExactly(maya.getId(), dad.getId(), mom.getId());
    }

    @Test
    void anUntaggedEventBelongsToTheCalendarsLaneMembers() {
        hub.getLaneMembers().add(dad);
        hub.getLaneMembers().add(maya);

        assertThat(attribution.membersOf(new Event().setSummary("Made in Google"), hub, family))
                .containsExactly(dad.getId(), maya.getId());
    }

    @Test
    void unknownAndMalformedIdsInTheTagAreIgnored() {
        hub.getLaneMembers().add(dad);

        assertThat(attribution.membersOf(tagged(UUID.randomUUID() + ", not-a-uuid ," + maya.getId()), hub, family))
                .containsExactly(maya.getId());
        // A tag naming nobody of this family falls back to the calendar's lanes.
        assertThat(attribution.membersOf(tagged(UUID.randomUUID().toString()), hub, family))
                .containsExactly(dad.getId());
    }

    @Test
    void invitedFamilyMembersAreAddedUnlessTheyDeclined() {
        hub.getLaneMembers().add(maya);
        Event invite = new Event().setAttendees(List.of(
                new EventAttendee().setEmail("MOM@example.com").setResponseStatus("accepted"),
                new EventAttendee().setEmail("dad@example.com").setResponseStatus("declined"),
                new EventAttendee().setEmail("friend@example.com").setResponseStatus("needsAction")));

        assertThat(attribution.membersOf(invite, hub, family)).containsExactly(mom.getId(), maya.getId());
    }
}
