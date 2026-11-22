package com.familyhub.demo.repository;

import com.familyhub.demo.config.TestcontainersConfig;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyColor;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.GoogleAccessRole;
import com.familyhub.demo.model.GoogleCalendar;
import com.familyhub.demo.model.GoogleCalendarAccess;
import com.familyhub.demo.model.GoogleEvent;
import com.familyhub.demo.model.GoogleEventStatus;
import com.familyhub.demo.model.GoogleOAuthToken;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Google event cache's window queries (Phase 4). A window is half-open: timed events
 * overlap [from, to), all-day events overlap [fromDate, toDate) using Google's exclusive
 * end date. Masters are always returned, because whether a series reaches the window is
 * only known once its rule is expanded.
 */
@DataJpaTest
@Import(TestcontainersConfig.class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ActiveProfiles("test")
class GoogleEventRepositoryTest {

    private static final Instant FROM = Instant.parse("2026-12-03T08:00:00Z");
    private static final Instant TO = Instant.parse("2026-12-04T08:00:00Z");
    private static final LocalDate FROM_DATE = LocalDate.parse("2026-12-03");
    private static final LocalDate TO_DATE = LocalDate.parse("2026-12-04");

    @Autowired
    GoogleEventRepository events;

    @Autowired
    GoogleCalendarRepository calendars;

    @Autowired
    TestEntityManager em;

    private Family family;
    private GoogleCalendar hub;
    private GoogleCalendar other;

    @BeforeEach
    void setUp() {
        family = new Family();
        family.setName("Family");
        family.setUsername("cache-" + UUID.randomUUID());
        family.setPasswordHash("$2a$10$dummyhashfortesting");
        family = em.persistFlushFind(family);
        hub = calendar("hub@group.calendar.google.com");
        other = calendar("other@group.calendar.google.com");
    }

    private GoogleCalendar calendar(String googleId) {
        GoogleCalendar calendar = new GoogleCalendar();
        calendar.setFamily(family);
        calendar.setGoogleCalendarId(googleId);
        calendar.setSummary(googleId);
        return em.persistFlushFind(calendar);
    }

    private GoogleEvent event(GoogleCalendar calendar, String googleId) {
        GoogleEvent event = new GoogleEvent();
        event.setCalendar(calendar);
        event.setGoogleEventId(googleId);
        event.setStatus(GoogleEventStatus.CONFIRMED);
        event.setTitle(googleId);
        event.setRaw("{\"id\":\"" + googleId + "\"}");
        return event;
    }

    private GoogleEvent timed(GoogleCalendar calendar, String googleId, String start, String end) {
        GoogleEvent event = event(calendar, googleId);
        event.setStartAt(Instant.parse(start));
        event.setEndAt(Instant.parse(end));
        return em.persist(event);
    }

    private GoogleEvent allDay(GoogleCalendar calendar, String googleId, String start, String exclusiveEnd) {
        GoogleEvent event = event(calendar, googleId);
        event.setAllDay(true);
        event.setStartDate(LocalDate.parse(start));
        event.setEndDate(LocalDate.parse(exclusiveEnd));
        return em.persist(event);
    }

    private static List<String> ids(List<GoogleEvent> found) {
        return found.stream().map(GoogleEvent::getGoogleEventId).sorted().toList();
    }

    @Test
    void singlesOverlappingTheWindowAreFoundAndTouchingEdgesAreNot() {
        timed(hub, "inside", "2026-12-03T17:00:00Z", "2026-12-03T18:00:00Z");
        timed(hub, "across-start", "2026-12-03T07:00:00Z", "2026-12-03T09:00:00Z");
        timed(hub, "ends-at-from", "2026-12-03T07:00:00Z", "2026-12-03T08:00:00Z");
        timed(hub, "starts-at-to", "2026-12-04T08:00:00Z", "2026-12-04T09:00:00Z");
        allDay(hub, "three-days", "2026-12-01", "2026-12-04");
        allDay(hub, "ended-day-before", "2026-12-02", "2026-12-03");
        allDay(hub, "starts-day-after", "2026-12-04", "2026-12-05");
        timed(other, "other-calendar", "2026-12-03T17:00:00Z", "2026-12-03T18:00:00Z");
        em.flush();

        assertThat(ids(events.findSinglesInWindow(Set.of(hub), FROM, TO, FROM_DATE, TO_DATE)))
                .containsExactly("across-start", "inside", "three-days");
    }

    @Test
    void cancelledSinglesMastersAndInstancesAreNotSingles() {
        GoogleEvent cancelled = timed(hub, "cancelled", "2026-12-03T17:00:00Z", "2026-12-03T18:00:00Z");
        cancelled.setStatus(GoogleEventStatus.CANCELLED);
        GoogleEvent master = timed(hub, "weekly", "2026-11-06T00:00:00Z", "2026-11-06T01:00:00Z");
        master.setRecurrence(new String[]{"RRULE:FREQ=WEEKLY;BYDAY=FR"});
        GoogleEvent instance = timed(hub, "weekly_20261204T000000Z", "2026-12-03T17:00:00Z",
                "2026-12-03T18:00:00Z");
        instance.setRecurringEventId("weekly");
        em.flush();

        assertThat(events.findSinglesInWindow(Set.of(hub), FROM, TO, FROM_DATE, TO_DATE)).isEmpty();
    }

    @Test
    void mastersComeBackWhateverTheWindowAndTheirInstancesByMasterId() {
        GoogleEvent weekly = allDay(hub, "weekly", "2025-01-03", "2025-01-04");
        weekly.setRecurrence(new String[]{"RRULE:FREQ=WEEKLY", "EXDATE;VALUE=DATE:20250110"});
        GoogleEvent cancelledSeries = allDay(hub, "gone", "2025-01-03", "2025-01-04");
        cancelledSeries.setRecurrence(new String[]{"RRULE:FREQ=DAILY"});
        cancelledSeries.setStatus(GoogleEventStatus.CANCELLED);
        GoogleEvent moved = timed(hub, "weekly_20261204", "2026-12-05T17:00:00Z", "2026-12-05T18:00:00Z");
        moved.setRecurringEventId("weekly");
        GoogleEvent dropped = event(hub, "weekly_20261211");
        dropped.setRecurringEventId("weekly");
        dropped.setStatus(GoogleEventStatus.CANCELLED);
        em.persist(dropped);
        GoogleEvent otherCalendarsInstance = timed(other, "weekly_20261218", "2026-12-18T17:00:00Z",
                "2026-12-18T18:00:00Z");
        otherCalendarsInstance.setRecurringEventId("weekly");
        em.flush();

        assertThat(ids(events.findMasters(Set.of(hub)))).containsExactly("weekly");
        // Cancelled instances are kept: they are what hides an occurrence of the series.
        assertThat(ids(events.findInstancesOf(Set.of(hub), Set.of("weekly"))))
                .containsExactly("weekly_20261204", "weekly_20261211");
    }

    @Test
    void arraysAndTheRawEventRoundTripAndAnEventIsFoundByItsGoogleId() {
        UUID maya = UUID.randomUUID();
        UUID dad = UUID.randomUUID();
        GoogleEvent event = timed(hub, "abc123", "2026-12-03T17:00:00Z", "2026-12-03T18:00:00Z");
        event.setRecurrence(new String[]{"RRULE:FREQ=WEEKLY;BYDAY=FR", "EXDATE;TZID=America/Los_Angeles:20261211T090000"});
        event.setMemberIds(new UUID[]{maya, dad});
        event.setRaw("{\"id\":\"abc123\",\"reminders\":{\"useDefault\":false}}");
        em.flush();
        em.clear();

        GoogleEvent found = events.findByCalendarAndGoogleEventId(hub, "abc123").orElseThrow();
        assertThat(found.getRecurrence()).containsExactly("RRULE:FREQ=WEEKLY;BYDAY=FR",
                "EXDATE;TZID=America/Los_Angeles:20261211T090000");
        assertThat(found.getMemberIds()).containsExactly(maya, dad);
        assertThat(found.getRaw()).isEqualTo("{\"id\":\"abc123\",\"reminders\":{\"useDefault\":false}}");
        assertThat(events.findByCalendarAndGoogleEventId(other, "abc123")).isEmpty();
    }

    @Test
    void aCalendarKeepsItsLanesAndEachAccountsAccessRole() {
        FamilyMember dad = new FamilyMember();
        dad.setFamily(family);
        dad.setName("Dad");
        dad.setColor(FamilyColor.CORAL);
        dad = em.persistFlushFind(dad);
        GoogleOAuthToken token = new GoogleOAuthToken();
        token.setMember(dad);
        token.setAccessToken("enc-access");
        token.setRefreshToken("enc-refresh");
        token.setTokenExpiry(Instant.now());
        token.setScope("calendar.events");
        GoogleOAuthToken saved = em.persistFlushFind(token);

        hub.getLaneMembers().add(dad);
        em.persist(new GoogleCalendarAccess(hub, saved, GoogleAccessRole.OWNER));
        em.flush();
        em.clear();

        GoogleCalendar found = calendars.findById(hub.getId()).orElseThrow();
        assertThat(found.getLaneMembers()).extracting(FamilyMember::getName).containsExactly("Dad");
        assertThat(calendars.findAccess(found)).singleElement().satisfies(access -> {
            assertThat(access.getToken().getId()).isEqualTo(saved.getId());
            assertThat(access.getAccessRole()).isEqualTo(GoogleAccessRole.OWNER);
            assertThat(access.getAccessRole().canWrite()).isTrue();
        });
        assertThat(GoogleAccessRole.fromGoogle("freeBusyReader").canWrite()).isFalse();
        assertThat(GoogleAccessRole.fromGoogle("writer").canWrite()).isTrue();
    }
}
