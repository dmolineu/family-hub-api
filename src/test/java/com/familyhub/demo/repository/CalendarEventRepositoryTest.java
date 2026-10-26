package com.familyhub.demo.repository;

import com.familyhub.demo.config.TestcontainersConfig;
import com.familyhub.demo.model.CalendarEvent;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyColor;
import com.familyhub.demo.model.FamilyMember;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(TestcontainersConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class CalendarEventRepositoryTest {

    @Autowired
    CalendarEventRepository calendarEventRepository;

    @Autowired
    FamilyRepository familyRepository;

    @Autowired
    FamilyMemberRepository familyMemberRepository;

    @Autowired
    TestEntityManager em;

    private Family persistFamily(String username) {
        Family family = new Family();
        family.setName("Family " + username);
        family.setUsername(username);
        family.setPasswordHash("$2a$10$dummyhashfortesting");
        return em.persistFlushFind(family);
    }

    private FamilyMember persistMember(Family family, String name) {
        FamilyMember member = new FamilyMember();
        member.setFamily(family);
        member.setName(name);
        member.setColor(FamilyColor.CORAL);
        return em.persistFlushFind(member);
    }

    private CalendarEvent persistEvent(Family family, FamilyMember member, String title) {
        CalendarEvent event = new CalendarEvent();
        event.setFamily(family);
        event.setMember(member);
        event.setTitle(title);
        event.setStartTime(LocalTime.of(9, 0));
        event.setEndTime(LocalTime.of(10, 0));
        event.setDate(LocalDate.of(2025, 6, 15));
        event.setAllDay(false);
        return em.persistFlushFind(event);
    }

    @Test
    void findByFamily_returnsEvents() {
        Family family = persistFamily("event_family_1");
        FamilyMember member = persistMember(family, "Alice");
        persistEvent(family, member, "Event 1");
        persistEvent(family, member, "Event 2");

        List<CalendarEvent> events = calendarEventRepository.findByFamily(family);

        assertThat(events).hasSize(2);
        assertThat(events).extracting(CalendarEvent::getTitle)
                .containsExactlyInAnyOrder("Event 1", "Event 2");
    }

    @Test
    void findByFamilyAndId_match_returnsEvent() {
        Family family = persistFamily("event_family_2");
        FamilyMember member = persistMember(family, "Alice");
        CalendarEvent event = persistEvent(family, member, "Target Event");

        Optional<CalendarEvent> found = calendarEventRepository.findByFamilyAndId(family, event.getId());

        assertThat(found).isPresent();
        assertThat(found.get().getTitle()).isEqualTo("Target Event");
    }

    @Test
    void findByFamilyAndId_wrongFamily_returnsEmpty() {
        Family family1 = persistFamily("event_family_3");
        Family family2 = persistFamily("event_family_4");
        FamilyMember member = persistMember(family1, "Alice");
        CalendarEvent event = persistEvent(family1, member, "Private Event");

        Optional<CalendarEvent> found = calendarEventRepository.findByFamilyAndId(family2, event.getId());

        assertThat(found).isEmpty();
    }

    @Test
    void findByFamilyAndId_wrongId_returnsEmpty() {
        Family family = persistFamily("event_family_5");

        Optional<CalendarEvent> found = calendarEventRepository.findByFamilyAndId(family, UUID.randomUUID());

        assertThat(found).isEmpty();
    }

    @Test
    void cascadeDelete_whenFamilyDeleted() {
        Family family = persistFamily("event_family_6");
        FamilyMember member = persistMember(family, "Alice");
        CalendarEvent event = persistEvent(family, member, "Cascade Event");

        UUID familyId = family.getId();
        UUID memberId = member.getId();
        UUID eventId = event.getId();

        em.clear();

        familyRepository.deleteById(familyId);
        familyRepository.flush();

        assertThat(em.find(Family.class, familyId)).isNull();
        assertThat(em.find(FamilyMember.class, memberId)).isNull();
        assertThat(em.find(CalendarEvent.class, eventId)).isNull();
    }

    /**
     * Events synced from Google keep Google's own limits, which are far longer than
     * 255 characters (a first sync failed on one, and imported nothing).
     */
    @Test
    void storesGoogleSizedTitlesLocationsAndRules() {
        Family family = persistFamily("event_family_long");
        FamilyMember member = persistMember(family, "Alice");
        String title = "Season schedule: " + "U10 Thunder vs. Lightning, ".repeat(20);
        String location = "Field 3, North Park Sports Complex, 1234 Long Address Way, "
                + "behind the library (use the south lot; gate code at the booth) ".repeat(6);
        String rule = "FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR;UNTIL=20271231T075959Z;"
                + "BYHOUR=9;BYMINUTE=0;BYSECOND=0;WKST=SU;INTERVAL=1;".repeat(5);

        CalendarEvent event = new CalendarEvent();
        event.setFamily(family);
        event.setMember(member);
        event.setTitle(title);
        event.setLocation(location);
        event.setRecurrenceRule(rule);
        event.setStartTime(LocalTime.of(9, 0));
        event.setEndTime(LocalTime.of(10, 0));
        event.setDate(LocalDate.of(2026, 10, 26));
        event.setAllDay(false);
        CalendarEvent saved = em.persistFlushFind(event);

        assertThat(saved.getTitle()).hasSizeGreaterThan(255).isEqualTo(title);
        assertThat(saved.getLocation()).hasSizeGreaterThan(255).isEqualTo(location);
        assertThat(saved.getRecurrenceRule()).hasSizeGreaterThan(255).isEqualTo(rule);
    }
}
