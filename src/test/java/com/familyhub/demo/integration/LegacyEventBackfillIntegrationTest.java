package com.familyhub.demo.integration;

import com.familyhub.demo.config.TestcontainersConfig;
import com.familyhub.demo.dto.calendar.CalendarEventResponse;
import com.familyhub.demo.google.FakeGoogleCalendarGateway;
import com.familyhub.demo.google.GoogleScopes;
import com.familyhub.demo.google.MemberAttribution;
import com.familyhub.demo.model.CalendarEvent;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyColor;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.GoogleAccessRole;
import com.familyhub.demo.model.GoogleCalendar;
import com.familyhub.demo.model.GoogleCalendarAccess;
import com.familyhub.demo.model.GoogleOAuthToken;
import com.familyhub.demo.model.InboxImport;
import com.familyhub.demo.model.InboxImportKind;
import com.familyhub.demo.model.InboxImportStatus;
import com.familyhub.demo.model.InboxMessage;
import com.familyhub.demo.model.InboxOutcome;
import com.familyhub.demo.repository.CalendarEventRepository;
import com.familyhub.demo.repository.FamilyMemberRepository;
import com.familyhub.demo.repository.FamilyRepository;
import com.familyhub.demo.repository.GoogleCalendarAccessRepository;
import com.familyhub.demo.repository.GoogleCalendarRepository;
import com.familyhub.demo.repository.GoogleEventRepository;
import com.familyhub.demo.repository.GoogleOAuthTokenRepository;
import com.familyhub.demo.repository.InboxImportRepository;
import com.familyhub.demo.repository.InboxMessageRepository;
import com.familyhub.demo.service.CalendarQueryService;
import com.familyhub.demo.service.LegacyEventBackfill;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Copying the family's own events (the ones the hub made before Phase 4, inbox events
 * among them) to its default Google calendar, once an account may write there. Each
 * keeps its person, lands once even if a run is cut off after Google took it, and its
 * inbox record follows it. Until then the calendar shows it, read-only.
 */
@SpringBootTest
@Import({TestcontainersConfig.class, LegacyEventBackfillIntegrationTest.Config.class})
@ActiveProfiles("test")
class LegacyEventBackfillIntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeGoogleCalendarGateway backfillFakeGoogle() {
            return new FakeGoogleCalendarGateway();
        }
    }

    @Autowired
    FakeGoogleCalendarGateway google;

    @Autowired
    LegacyEventBackfill backfill;

    @Autowired
    CalendarQueryService query;

    @Autowired
    FamilyRepository families;

    @Autowired
    FamilyMemberRepository members;

    @Autowired
    GoogleOAuthTokenRepository tokens;

    @Autowired
    GoogleCalendarRepository calendars;

    @Autowired
    GoogleCalendarAccessRepository access;

    @Autowired
    GoogleEventRepository events;

    @Autowired
    CalendarEventRepository legacy;

    @Autowired
    InboxMessageRepository messages;

    @Autowired
    InboxImportRepository imports;

    @Autowired
    PlatformTransactionManager transactionManager;

    private Family family;
    private FamilyMember dad;
    private FamilyMember maya;
    private GoogleOAuthToken account;
    private GoogleCalendar hub;

    @BeforeEach
    void setUp() {
        Family created = new Family();
        created.setName("Backfill family");
        created.setUsername("backfill-" + UUID.randomUUID());
        created.setPasswordHash("$2a$10$dummyhashfortesting");
        family = families.save(created);
        dad = member("Dad");
        maya = member("Maya");
        GoogleOAuthToken token = new GoogleOAuthToken();
        token.setMember(dad);
        token.setAccessToken("enc-access");
        token.setRefreshToken("enc-refresh");
        token.setTokenExpiry(Instant.now().plusSeconds(3600));
        token.setScope(GoogleScopes.REQUESTED);
        account = tokens.save(token);
        String googleId = "hub-" + UUID.randomUUID() + "@group.calendar.google.com";
        google.addCalendar(googleId, "Hub", "America/Los_Angeles");
        google.grant(account, googleId, GoogleAccessRole.OWNER);
        GoogleCalendar calendar = new GoogleCalendar();
        calendar.setFamily(family);
        calendar.setGoogleCalendarId(googleId);
        calendar.setSummary("Hub");
        calendar.setTimeZone("America/Los_Angeles");
        calendar.setPollingToken(account);
        calendar.getLaneMembers().add(dad);
        hub = calendars.save(calendar);
        access.save(new GoogleCalendarAccess(hub, account, GoogleAccessRole.OWNER));
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                families.findById(family.getId()).orElseThrow().setDefaultGoogleCalendarId(hub.getId()));
        family = families.findById(family.getId()).orElseThrow();
    }

    private FamilyMember member(String name) {
        FamilyMember member = new FamilyMember();
        member.setFamily(family);
        member.setName(name);
        member.setColor(FamilyColor.CORAL);
        return members.save(member);
    }

    private CalendarEvent own(String title, String date, String start, String end, FamilyMember owner) {
        CalendarEvent event = new CalendarEvent();
        event.setFamily(family);
        event.setMember(owner);
        event.setTitle(title);
        event.setDate(LocalDate.parse(date));
        event.setStartTime(LocalTime.parse(start));
        event.setEndTime(LocalTime.parse(end));
        return event;
    }

    private CalendarEvent single() {
        CalendarEvent class1 = own("School", "2026-11-22", "09:30", "12:00", maya);
        class1.setLocation("Room 4");
        return legacy.save(class1);
    }

    private InboxImport linkedImport(CalendarEvent event) {
        InboxMessage message = new InboxMessage();
        message.setFamily(family);
        message.setMessageId("<flyer-" + UUID.randomUUID() + "@example.com>");
        message.setOutcome(InboxOutcome.EVENTS);
        message.setProcessedAt(Instant.now());
        message = messages.save(message);
        InboxImport added = new InboxImport();
        added.setFamily(family);
        added.setMessage(message);
        added.setKind(InboxImportKind.EVENT);
        added.setStatus(InboxImportStatus.ADDED);
        added.setTitle(event.getTitle());
        added.setCalendarEventId(event.getId());
        added.setCreatedAt(Instant.now());
        return imports.save(added);
    }

    private Event inGoogle(String legacyId) {
        return google.events(hub.getGoogleCalendarId()).stream()
                .filter(e -> e.getExtendedProperties() != null
                        && legacyId.equals(e.getExtendedProperties().getShared().get(LegacyEventBackfill.LEGACY_ID_PROPERTY)))
                .findFirst().orElseThrow();
    }

    @Test
    void eachEventIsCopiedOnceWithItsPersonAndTheInboxRecordFollowsIt() {
        CalendarEvent school = single();
        InboxImport fromEmail = linkedImport(school);
        CalendarEvent trip = own("Trip", "2026-12-24", "00:00", "00:00", dad);
        trip.setAllDay(true);
        trip.setEndDate(LocalDate.parse("2026-12-25"));
        trip = legacy.save(trip);
        CalendarEvent weekly = own("Class", "2026-10-23", "16:00", "17:00", maya);
        weekly.setRecurrenceRule("FREQ=WEEKLY;BYDAY=FR");
        weekly.setExdates("2026-11-27");
        weekly = legacy.save(weekly);

        assertThat(backfill.copyFamily(family.getId())).isEqualTo(3);

        Event copied = inGoogle(school.getId().toString());
        assertThat(copied.getSummary()).isEqualTo("School");
        assertThat(copied.getLocation()).isEqualTo("Room 4");
        assertThat(copied.getStart().getDateTime().getValue())
                .isEqualTo(Instant.parse("2026-11-22T17:30:00Z").toEpochMilli());
        assertThat(copied.getEnd().getDateTime().getValue())
                .isEqualTo(Instant.parse("2026-11-22T20:00:00Z").toEpochMilli());
        assertThat(copied.getStart().getTimeZone()).isEqualTo("America/Los_Angeles");
        assertThat(copied.getExtendedProperties().getShared())
                .containsEntry(MemberAttribution.MEMBERS_PROPERTY, maya.getId().toString());

        Event tripCopy = inGoogle(trip.getId().toString());
        assertThat(tripCopy.getStart().getDate().toStringRfc3339()).isEqualTo("2026-12-24");
        assertThat(tripCopy.getEnd().getDate().toStringRfc3339()).isEqualTo("2026-12-26");
        assertThat(inGoogle(weekly.getId().toString()).getRecurrence()).containsExactly(
                "RRULE:FREQ=WEEKLY;BYDAY=FR", "EXDATE;TZID=America/Los_Angeles:20261127T160000");

        CalendarEvent marked = legacy.findById(school.getId()).orElseThrow();
        assertThat(marked.getCopiedAt()).isNotNull();
        assertThat(marked.getCopiedGoogleEventId()).isEqualTo(copied.getId());
        assertThat(marked.getCopiedCalendarId()).isEqualTo(hub.getId());
        assertThat(events.findAllByCalendarId(hub.getId())).hasSize(3);
        InboxImport repointed = imports.findById(fromEmail.getId()).orElseThrow();
        assertThat(repointed.getGoogleCalendarId()).isEqualTo(hub.getId());
        assertThat(repointed.getGoogleEventId()).isEqualTo(copied.getId());

        assertThat(backfill.copyFamily(family.getId())).isZero();
        assertThat(google.events(hub.getGoogleCalendarId())).hasSize(3);
    }

    @Test
    void aCopyGoogleAlreadyHasFromACutOffRunIsUsedInsteadOfASecondOne() {
        CalendarEvent school = single();
        Event earlier = google.insertEvent(account, hub.getGoogleCalendarId(), new Event().setSummary("School")
                .setStart(new EventDateTime().setDateTime(new DateTime("2026-11-22T17:30:00Z")))
                .setEnd(new EventDateTime().setDateTime(new DateTime("2026-11-22T20:00:00Z")))
                .setExtendedProperties(new Event.ExtendedProperties().setShared(Map.of(
                        LegacyEventBackfill.LEGACY_ID_PROPERTY, school.getId().toString()))), false);

        assertThat(backfill.copyFamily(family.getId())).isEqualTo(1);

        assertThat(google.events(hub.getGoogleCalendarId())).hasSize(1);
        assertThat(legacy.findById(school.getId()).orElseThrow().getCopiedGoogleEventId()).isEqualTo(earlier.getId());
    }

    @Test
    void withoutAnAccountThatMayWriteNothingIsCopied() {
        CalendarEvent school = single();
        GoogleOAuthToken old = tokens.findById(account.getId()).orElseThrow();
        old.setScope("https://www.googleapis.com/auth/calendar.events.readonly");
        tokens.save(old);

        assertThat(backfill.copyFamily(family.getId())).isZero();

        assertThat(google.events(hub.getGoogleCalendarId())).isEmpty();
        assertThat(legacy.findById(school.getId()).orElseThrow().getCopiedAt()).isNull();
    }

    @Test
    void aCancelledDateBecomesAnExdateAndAChangedDateItsOwnOccurrence() {
        CalendarEvent weekly = own("Class", "2026-11-06", "16:00", "17:00", maya);
        weekly.setRecurrenceRule("FREQ=WEEKLY;BYDAY=FR");
        weekly = legacy.save(weekly);
        CalendarEvent cancelled = own("Class", "2026-11-13", "16:00", "17:00", maya);
        cancelled.setRecurringEvent(weekly);
        cancelled.setOriginalDate(LocalDate.parse("2026-11-13"));
        cancelled.setCancelled(true);
        legacy.save(cancelled);
        CalendarEvent moved = own("Class (moved)", "2026-11-21", "10:00", "11:00", maya);
        moved.setRecurringEvent(weekly);
        moved.setOriginalDate(LocalDate.parse("2026-11-20"));
        legacy.save(moved);

        assertThat(backfill.copyFamily(family.getId())).isEqualTo(1);

        Event master = inGoogle(weekly.getId().toString());
        assertThat(master.getRecurrence()).containsExactly("RRULE:FREQ=WEEKLY;BYDAY=FR",
                "EXDATE;TZID=America/Los_Angeles:20261113T160000");
        assertThat(google.events(hub.getGoogleCalendarId())).filteredOn(e -> master.getId().equals(e.getRecurringEventId()))
                .singleElement().satisfies(instance -> {
                    assertThat(instance.getSummary()).isEqualTo("Class (moved)");
                    assertThat(instance.getStart().getDateTime().getValue())
                            .isEqualTo(Instant.parse("2026-11-21T18:00:00Z").toEpochMilli());
                });
        assertThat(legacy.findById(moved.getId()).orElseThrow().getCopiedAt()).isNotNull();
    }

    @Test
    void theCalendarShowsAnUncopiedEventReadOnlyAndTheCopyOnceItIsMade() {
        CalendarEvent school = single();

        List<CalendarEventResponse> before = query.eventsFor(family, LocalDate.parse("2026-11-22"),
                LocalDate.parse("2026-11-22"), null);
        assertThat(before).singleElement().satisfies(event -> {
            assertThat(event.title()).isEqualTo("School");
            assertThat(event.start()).isEqualTo("2026-11-22T17:30:00Z");
            assertThat(event.memberIds()).containsExactly(maya.getId());
            assertThat(event.canEdit()).isFalse();
            assertThat(event.id()).isEqualTo(school.getId());
        });

        backfill.copyFamily(family.getId());

        assertThat(query.eventsFor(family, LocalDate.parse("2026-11-22"), LocalDate.parse("2026-11-22"), null))
                .singleElement().satisfies(event -> {
                    assertThat(event.canEdit()).isTrue();
                    assertThat(event.calendarId()).isEqualTo(hub.getId());
                });
    }
}
