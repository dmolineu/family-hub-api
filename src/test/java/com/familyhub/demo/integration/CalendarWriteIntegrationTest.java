package com.familyhub.demo.integration;

import com.familyhub.demo.config.TestcontainersConfig;
import com.familyhub.demo.dto.calendar.CalendarEventResponse;
import com.familyhub.demo.dto.calendar.CalendarWriteRequest;
import com.familyhub.demo.event.CalendarSyncRequested;
import com.familyhub.demo.exception.CalendarWriteException;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.google.FakeGoogleCalendarGateway;
import com.familyhub.demo.google.GoogleCalendarException;
import com.familyhub.demo.google.GoogleScopes;
import com.familyhub.demo.google.MemberAttribution;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyColor;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.GoogleAccessRole;
import com.familyhub.demo.model.GoogleCalendar;
import com.familyhub.demo.model.GoogleCalendarAccess;
import com.familyhub.demo.model.GoogleEvent;
import com.familyhub.demo.model.GoogleOAuthToken;
import com.familyhub.demo.repository.FamilyMemberRepository;
import com.familyhub.demo.repository.FamilyRepository;
import com.familyhub.demo.repository.GoogleCalendarAccessRepository;
import com.familyhub.demo.repository.GoogleCalendarRepository;
import com.familyhub.demo.repository.GoogleEventRepository;
import com.familyhub.demo.repository.GoogleOAuthTokenRepository;
import com.familyhub.demo.service.CalendarCacheSync;
import com.familyhub.demo.service.CalendarWriteService;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import com.google.api.services.calendar.model.EventReminder;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.event.ApplicationEvents;
import org.springframework.test.context.event.RecordApplicationEvents;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Writing events (Phase 4): Google first, then the cache from Google's answer. New events
 * go on the family's default calendar (or the one asked for) with the hub's member tag;
 * an edit sends back Google's whole event, so what the hub does not show survives; a
 * stale etag is a conflict that triggers a sync. Google's failures become error codes
 * the app can explain, and never a 401, which would sign the app out.
 */
@SpringBootTest
@Import({TestcontainersConfig.class, CalendarWriteIntegrationTest.Config.class})
@ActiveProfiles("test")
@RecordApplicationEvents
class CalendarWriteIntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeGoogleCalendarGateway writeFakeGoogle() {
            return new FakeGoogleCalendarGateway();
        }
    }

    @Autowired
    FakeGoogleCalendarGateway google;

    @Autowired
    CalendarWriteService writes;

    @Autowired
    CalendarCacheSync sync;

    @Autowired
    ApplicationEvents published;

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
    PlatformTransactionManager transactionManager;

    private Family family;
    private FamilyMember dad;
    private FamilyMember maya;
    private GoogleOAuthToken account;
    private GoogleCalendar hub;
    private GoogleCalendar other;

    @BeforeEach
    void setUp() {
        family = new Family();
        family.setName("Write family");
        family.setUsername("write-" + UUID.randomUUID());
        family.setPasswordHash("$2a$10$dummyhashfortesting");
        family = families.save(family);
        dad = member("Dad");
        maya = member("Maya");
        GoogleOAuthToken token = new GoogleOAuthToken();
        token.setMember(dad);
        token.setAccessToken("enc-access");
        token.setRefreshToken("enc-refresh");
        token.setTokenExpiry(Instant.now().plusSeconds(3600));
        token.setScope(GoogleScopes.REQUESTED);
        account = tokens.save(token);
        hub = calendar("hub", GoogleAccessRole.OWNER);
        other = calendar("other", GoogleAccessRole.WRITER);
        setDefault(hub.getId());
    }

    // On the managed row: saving the detached family would merge its empty member list,
    // and orphan removal would delete the members.
    private void setDefault(UUID calendarId) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                families.findById(family.getId()).orElseThrow().setDefaultGoogleCalendarId(calendarId));
        family = families.findById(family.getId()).orElseThrow();
    }

    private FamilyMember member(String name) {
        FamilyMember member = new FamilyMember();
        member.setFamily(family);
        member.setName(name);
        member.setColor(FamilyColor.CORAL);
        return members.save(member);
    }

    private GoogleCalendar calendar(String name, GoogleAccessRole role) {
        String googleId = name + "-" + UUID.randomUUID() + "@group.calendar.google.com";
        google.addCalendar(googleId, name, "America/Los_Angeles");
        google.grant(account, googleId, role);
        GoogleCalendar calendar = new GoogleCalendar();
        calendar.setFamily(family);
        calendar.setGoogleCalendarId(googleId);
        calendar.setSummary(name);
        calendar.setTimeZone("America/Los_Angeles");
        calendar.setPollingToken(account);
        calendar.getLaneMembers().add(dad);
        calendar = calendars.save(calendar);
        access.save(new GoogleCalendarAccess(calendar, account, role));
        return calendar;
    }

    private static CalendarWriteRequest timed(String title, String start, String end, UUID... memberIds) {
        return new CalendarWriteRequest(null, List.of(memberIds), title, "Bring a towel", "Pool", false,
                Instant.parse(start), Instant.parse(end), null, null, "America/Los_Angeles", null, false, null);
    }

    private Event inGoogle(GoogleCalendar calendar, String googleId) {
        return google.getEvent(account, calendar.getGoogleCalendarId(), googleId);
    }

    private GoogleEvent cached(UUID rowId) {
        return events.findById(rowId).orElseThrow();
    }

    private CalendarWriteException.Code codeOf(Runnable write) {
        try {
            write.run();
        } catch (CalendarWriteException e) {
            return e.getCode();
        }
        throw new AssertionError("expected a CalendarWriteException");
    }

    @Test
    void aNewEventGoesOnTheDefaultCalendarWithItsMembersTagAndIsCachedAtOnce() {
        CalendarEventResponse created = writes.create(family,
                timed("Swim", "2026-12-03T17:00:00Z", "2026-12-03T18:00:00Z", maya.getId(), dad.getId()));

        Event event = inGoogle(hub, created.googleEventId());
        assertThat(event.getSummary()).isEqualTo("Swim");
        assertThat(event.getDescription()).isEqualTo("Bring a towel");
        assertThat(event.getLocation()).isEqualTo("Pool");
        assertThat(event.getStart().getDateTime().getValue()).isEqualTo(Instant.parse("2026-12-03T17:00:00Z").toEpochMilli());
        assertThat(event.getStart().getTimeZone()).isEqualTo("America/Los_Angeles");
        assertThat(event.getExtendedProperties().getShared().get(MemberAttribution.MEMBERS_PROPERTY))
                .isEqualTo(maya.getId() + "," + dad.getId());

        assertThat(created.calendarId()).isEqualTo(hub.getId());
        // Family order; these members tie on sort order, so only the set is fixed.
        assertThat(created.memberIds()).containsExactlyInAnyOrder(dad.getId(), maya.getId());
        assertThat(created.canEdit()).isTrue();
        assertThat(created.start()).isEqualTo("2026-12-03T17:00:00Z");
        assertThat(cached(created.id()).getEtag()).isEqualTo(event.getEtag());
        assertThat(published.stream(CalendarSyncRequested.class))
                .contains(new CalendarSyncRequested(hub.getId()));
    }

    @Test
    void anAllDaySeriesKeepsGooglesExclusiveEndAndItsRule() {
        CalendarEventResponse created = writes.create(family, new CalendarWriteRequest(other.getId(),
                List.of(maya.getId()), "Camp", null, null, true, null, null, LocalDate.parse("2026-12-04"),
                LocalDate.parse("2026-12-07"), null, List.of("RRULE:FREQ=WEEKLY;COUNT=2"), false, null));

        Event event = inGoogle(other, created.googleEventId());
        assertThat(event.getStart().getDate().toStringRfc3339()).isEqualTo("2026-12-04");
        assertThat(event.getEnd().getDate().toStringRfc3339()).isEqualTo("2026-12-07");
        assertThat(event.getRecurrence()).containsExactly("RRULE:FREQ=WEEKLY;COUNT=2");
        assertThat(created.recurring()).isTrue();
        assertThat(created.recurrence()).containsExactly("RRULE:FREQ=WEEKLY;COUNT=2");
        assertThat(created.allDay()).isTrue();
        assertThat(created.end()).isEqualTo("2026-12-07");
    }

    @Test
    void aTimedSeriesRepeatingUntilADateReachesGoogleEndingAtTheEndOfThatDay() {
        // The app's "repeat until" is a date; Google and RFC 5545 want UTC for a timed series.
        CalendarEventResponse created = writes.create(family, new CalendarWriteRequest(null, List.of(dad.getId()),
                "Practice", null, null, false, Instant.parse("2026-12-01T17:00:00Z"),
                Instant.parse("2026-12-01T18:00:00Z"), null, null, "America/Los_Angeles",
                List.of("RRULE:FREQ=DAILY;UNTIL=20261205"), false, null));

        assertThat(inGoogle(hub, created.googleEventId()).getRecurrence())
                .containsExactly("RRULE:FREQ=DAILY;UNTIL=20261206T075959Z");
    }

    @Test
    void anEditSendsBackGooglesWholeEventSoWhatTheHubDoesNotShowSurvives() {
        Event made = google.insertEvent(account, hub.getGoogleCalendarId(), new Event().setSummary("Party")
                .setStart(new EventDateTime().setDateTime(new DateTime("2026-12-05T01:00:00Z")))
                .setEnd(new EventDateTime().setDateTime(new DateTime("2026-12-05T03:00:00Z")))
                .setColorId("5")
                .setReminders(new Event.Reminders().setUseDefault(false)
                        .setOverrides(List.of(new EventReminder().setMethod("popup").setMinutes(30))))
                .setExtendedProperties(new Event.ExtendedProperties().setPrivate(Map.of("keep", "me"))), false);
        sync.syncCalendar(hub.getId());
        GoogleEvent row = events.findByCalendarAndGoogleEventId(hub, made.getId()).orElseThrow();

        CalendarEventResponse updated = writes.update(family, row.getId(),
                new CalendarWriteRequest(null, List.of(maya.getId()), "Birthday party", null, "The park", false,
                        Instant.parse("2026-12-05T02:00:00Z"), Instant.parse("2026-12-05T04:00:00Z"), null, null,
                        null, null, false, row.getEtag()));

        Event event = inGoogle(hub, made.getId());
        assertThat(event.getSummary()).isEqualTo("Birthday party");
        assertThat(event.getLocation()).isEqualTo("The park");
        assertThat(event.getColorId()).isEqualTo("5");
        assertThat(event.getReminders().getOverrides()).singleElement()
                .satisfies(reminder -> assertThat(reminder.getMinutes()).isEqualTo(30));
        assertThat(event.getExtendedProperties().getPrivate()).containsEntry("keep", "me");
        assertThat(event.getExtendedProperties().getShared())
                .containsEntry(MemberAttribution.MEMBERS_PROPERTY, maya.getId().toString());
        assertThat(updated.id()).isEqualTo(row.getId());
        assertThat(updated.title()).isEqualTo("Birthday party");
        assertThat(cached(row.getId()).getEtag()).isEqualTo(event.getEtag());
    }

    @Test
    void aStaleEtagIsAConflictAndAsksForASync() {
        CalendarEventResponse created = writes.create(family,
                timed("Swim", "2026-12-03T17:00:00Z", "2026-12-03T18:00:00Z", dad.getId()));
        google.editInGoogle(hub.getGoogleCalendarId(), created.googleEventId(), e -> e.setSummary("Changed in Google"));
        published.clear();

        assertThat(codeOf(() -> writes.update(family, created.id(), timed("Mine", "2026-12-03T17:00:00Z",
                "2026-12-03T18:00:00Z", dad.getId()).withEtag(created.etag()))))
                .isEqualTo(CalendarWriteException.Code.EVENT_CONFLICT);
        assertThat(published.stream(CalendarSyncRequested.class)).contains(new CalendarSyncRequested(hub.getId()));
        assertThat(inGoogle(hub, created.googleEventId()).getSummary()).isEqualTo("Changed in Google");
    }

    @Test
    void deletingASeriesTakesItsOccurrencesOutOfGoogleAndTheCache() {
        CalendarEventResponse series = writes.create(family, new CalendarWriteRequest(null, List.of(dad.getId()),
                "Class", null, null, false, Instant.parse("2026-11-06T17:00:00Z"),
                Instant.parse("2026-11-06T18:00:00Z"), null, null, "America/Los_Angeles",
                List.of("RRULE:FREQ=WEEKLY;BYDAY=FR"), false, null));
        Event moved = google.getInstance(account, hub.getGoogleCalendarId(), series.googleEventId(),
                new EventDateTime().setDateTime(new DateTime("2026-11-13T17:00:00Z")).setTimeZone("America/Los_Angeles"));
        google.updateEvent(account, hub.getGoogleCalendarId(), moved.getId(), moved.clone().setSummary("Moved"),
                moved.getEtag(), false);
        sync.syncCalendar(hub.getId());
        assertThat(events.findAllByCalendarId(hub.getId())).hasSize(2);

        writes.delete(family, series.id(), cached(series.id()).getEtag());

        assertThat(google.events(hub.getGoogleCalendarId())).isEmpty();
        assertThat(events.findAllByCalendarId(hub.getId())).isEmpty();
    }

    @Test
    void choosingAnotherCalendarMovesTheEvent() {
        CalendarEventResponse created = writes.create(family,
                timed("Dentist", "2026-12-03T17:00:00Z", "2026-12-03T18:00:00Z", dad.getId()));

        CalendarEventResponse moved = writes.update(family, created.id(), new CalendarWriteRequest(other.getId(),
                List.of(dad.getId()), "Dentist", null, null, false, Instant.parse("2026-12-03T17:00:00Z"),
                Instant.parse("2026-12-03T18:00:00Z"), null, null, null, null, false, created.etag()));

        assertThat(moved.calendarId()).isEqualTo(other.getId());
        assertThat(google.events(hub.getGoogleCalendarId())).isEmpty();
        assertThat(google.events(other.getGoogleCalendarId())).extracting(Event::getSummary).containsExactly("Dentist");
        assertThat(events.findAllByCalendarId(hub.getId())).isEmpty();
        assertThat(events.findAllByCalendarId(other.getId())).hasSize(1);
    }

    @Test
    void withoutAWritableCalendarOrSignInTheWriteIsRefusedWithAReason() {
        setDefault(null);
        Family noDefault = family;
        assertThat(codeOf(() -> writes.create(noDefault, timed("X", "2026-12-03T17:00:00Z", "2026-12-03T18:00:00Z",
                dad.getId())))).isEqualTo(CalendarWriteException.Code.GOOGLE_NOT_CONNECTED);

        GoogleCalendar readOnly = calendar("readonly", GoogleAccessRole.READER);
        assertThat(codeOf(() -> writes.create(noDefault, timed("X", "2026-12-03T17:00:00Z", "2026-12-03T18:00:00Z",
                dad.getId()).onCalendar(readOnly.getId()))))
                .isEqualTo(CalendarWriteException.Code.GOOGLE_NO_WRITE_ACCESS);

        GoogleOAuthToken old = tokens.findById(account.getId()).orElseThrow();
        old.setScope("https://www.googleapis.com/auth/calendar.events.readonly");
        tokens.save(old);
        assertThat(codeOf(() -> writes.create(noDefault, timed("X", "2026-12-03T17:00:00Z", "2026-12-03T18:00:00Z",
                dad.getId()).onCalendar(hub.getId()))))
                .isEqualTo(CalendarWriteException.Code.GOOGLE_REAUTH_REQUIRED);
    }

    @Test
    void googlesFailuresBecomeCodesThatNeverSignTheAppOut() {
        google.failNextWith(GoogleCalendarException.Kind.UNAVAILABLE);
        CalendarWriteException unavailable = org.assertj.core.api.Assertions.catchThrowableOfType(
                CalendarWriteException.class, () -> writes.create(family,
                        timed("X", "2026-12-03T17:00:00Z", "2026-12-03T18:00:00Z", dad.getId())));
        assertThat(unavailable.getCode()).isEqualTo(CalendarWriteException.Code.GOOGLE_UNAVAILABLE);
        assertThat(unavailable.getStatus().value()).isEqualTo(503);

        google.failNextWith(GoogleCalendarException.Kind.REAUTH_REQUIRED);
        CalendarWriteException reauth = org.assertj.core.api.Assertions.catchThrowableOfType(
                CalendarWriteException.class, () -> writes.create(family,
                        timed("X", "2026-12-03T17:00:00Z", "2026-12-03T18:00:00Z", dad.getId())));
        assertThat(reauth.getCode()).isEqualTo(CalendarWriteException.Code.GOOGLE_REAUTH_REQUIRED);
        assertThat(reauth.getStatus().value()).isEqualTo(403);
        assertThat(tokens.findById(account.getId()).orElseThrow().isNeedsReauth()).isTrue();

        assertThat(java.util.Arrays.stream(CalendarWriteException.Code.values()).map(c -> c.status().value()))
                .doesNotContain(401);
        assertThat(CalendarWriteException.Code.GOOGLE_RATE_LIMITED.status().value()).isEqualTo(503);
        assertThat(new CalendarWriteException(CalendarWriteException.Code.GOOGLE_RATE_LIMITED, "slow",
                Duration.ofSeconds(90)).getRetryAfter()).contains(Duration.ofSeconds(90));
    }

    @Test
    void anotherFamilysMemberOrEventCannotBeUsed() {
        Family stranger = new Family();
        stranger.setName("Other");
        stranger.setUsername("other-" + UUID.randomUUID());
        stranger.setPasswordHash("$2a$10$dummyhashfortesting");
        Family strangers = families.save(stranger);
        CalendarEventResponse created = writes.create(family,
                timed("Swim", "2026-12-03T17:00:00Z", "2026-12-03T18:00:00Z", dad.getId()));

        assertThatThrownBy(() -> writes.delete(strangers, created.id(), created.etag()))
                .isInstanceOf(ResourceNotFoundException.class);
        FamilyMember outsider = new FamilyMember();
        outsider.setFamily(strangers);
        outsider.setName("Outsider");
        outsider.setColor(FamilyColor.CORAL);
        FamilyMember saved = members.save(outsider);
        assertThatThrownBy(() -> writes.create(family,
                timed("X", "2026-12-03T17:00:00Z", "2026-12-03T18:00:00Z", saved.getId())))
                .isInstanceOf(ResourceNotFoundException.class);
    }
}
