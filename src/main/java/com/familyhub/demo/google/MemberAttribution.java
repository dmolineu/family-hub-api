package com.familyhub.demo.google;

import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.GoogleCalendar;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventAttendee;
import org.springframework.stereotype.Component;

import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Decides whose lanes a Google event goes in (Phase 4). An event the hub wrote names its
 * members in the shared extended property {@value #MEMBERS_PROPERTY}, which wins;
 * otherwise the calendar's lane members get it. Family members invited by email who have
 * not declined are added either way. Only this family's members count, so a deleted
 * member or a tag edited by hand never breaks a read.
 */
@Component
public class MemberAttribution {

    /** The shared extended property holding the hub's member ids, comma-separated. */
    public static final String MEMBERS_PROPERTY = "familyhubMembers";

    /**
     * The members an event belongs to.
     *
     * @param event         the Google event
     * @param calendar      the cached calendar it is on
     * @param familyMembers the family's members, in the order results are returned
     * @return the members' ids, in the family's order
     */
    public List<UUID> membersOf(Event event, GoogleCalendar calendar, List<FamilyMember> familyMembers) {
        Set<UUID> family = familyMembers.stream().map(FamilyMember::getId).collect(Collectors.toSet());
        Set<UUID> chosen = new HashSet<>(tagged(event, family));
        if (chosen.isEmpty()) {
            calendar.getLaneMembers().stream().map(FamilyMember::getId).filter(family::contains).forEach(chosen::add);
        }
        if (event.getAttendees() != null) {
            for (EventAttendee attendee : event.getAttendees()) {
                if (attendee.getEmail() == null || "declined".equals(attendee.getResponseStatus())) {
                    continue;
                }
                String email = attendee.getEmail().toLowerCase(Locale.ROOT);
                familyMembers.stream()
                        .filter(member -> member.getEmail() != null
                                && member.getEmail().toLowerCase(Locale.ROOT).equals(email))
                        .forEach(member -> chosen.add(member.getId()));
            }
        }
        return familyMembers.stream().map(FamilyMember::getId).filter(chosen::contains).toList();
    }

    /**
     * The tag value naming these members, for the hub's own writes.
     *
     * @param memberIds the members
     * @return the comma-separated ids
     */
    public static String tag(Collection<UUID> memberIds) {
        return memberIds.stream().map(UUID::toString).collect(Collectors.joining(","));
    }

    private static Set<UUID> tagged(Event event, Set<UUID> family) {
        if (event.getExtendedProperties() == null || event.getExtendedProperties().getShared() == null) {
            return Set.of();
        }
        String value = event.getExtendedProperties().getShared().get(MEMBERS_PROPERTY);
        if (value == null) {
            return Set.of();
        }
        Set<UUID> ids = new HashSet<>();
        for (String part : value.split(",")) {
            try {
                UUID id = UUID.fromString(part.trim());
                if (family.contains(id)) {
                    ids.add(id);
                }
            } catch (IllegalArgumentException e) {
                // Not an id: someone edited the tag in Google. Skip it.
            }
        }
        return ids;
    }
}
