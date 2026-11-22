package com.familyhub.demo.integration;

import com.familyhub.demo.config.TestcontainersConfig;
import com.familyhub.demo.dto.calendar.CalendarEventResponse;
import com.familyhub.demo.dto.calendar.CalendarWriteRequest;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.google.FakeGoogleCalendarGateway;
import com.familyhub.demo.google.GoogleScopes;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyColor;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.GoogleAccessRole;
import com.familyhub.demo.model.GoogleCalendar;
import com.familyhub.demo.model.GoogleCalendarAccess;
import com.familyhub.demo.model.GoogleOAuthToken;
import com.familyhub.demo.repository.FamilyMemberRepository;
import com.familyhub.demo.repository.FamilyRepository;
import com.familyhub.demo.repository.GoogleCalendarAccessRepository;
import com.familyhub.demo.repository.GoogleCalendarRepository;
import com.familyhub.demo.repository.GoogleOAuthTokenRepository;
import com.familyhub.demo.service.CalendarQueryService;
import com.familyhub.demo.service.CalendarWriteService;
import com.google.api.services.calendar.model.Event;
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
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Changing part of a series (Phase 4), checked in Google and on the calendar: one date
 * changed or cancelled on its own; "this and following" splitting the series so the old
 * one ends before the date and a new one carries the change; deleting "this and
 * following" ending the series. Fridays at 16:00 Pacific from 2026-11-06.
 */
@SpringBootTest
@Import({TestcontainersConfig.class, CalendarSeriesWriteIntegrationTest.Config.class})
@ActiveProfiles("test")
class CalendarSeriesWriteIntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeGoogleCalendarGateway seriesFakeGoogle() {
            return new FakeGoogleCalendarGateway();
        }
    }

    private static final String NOV_13 = "20261114T000000Z";
    private static final String NOV_20 = "20261121T000000Z";

    @Autowired
    FakeGoogleCalendarGateway google;

    @Autowired
    CalendarWriteService writes;

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
    PlatformTransactionManager transactionManager;

    private Family family;
    private FamilyMember maya;
    private GoogleOAuthToken account;
    private GoogleCalendar hub;
    private CalendarEventResponse series;

    @BeforeEach
    void setUp() {
        Family created = new Family();
        created.setName("Series family");
        created.setUsername("series-" + UUID.randomUUID());
        created.setPasswordHash("$2a$10$dummyhashfortesting");
        family = families.save(created);
        FamilyMember member = new FamilyMember();
        member.setFamily(family);
        member.setName("Maya");
        member.setColor(FamilyColor.CORAL);
        maya = members.save(member);
        GoogleOAuthToken token = new GoogleOAuthToken();
        token.setMember(maya);
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
        calendar.getLaneMembers().add(maya);
        hub = calendars.save(calendar);
        access.save(new GoogleCalendarAccess(hub, account, GoogleAccessRole.OWNER));
        new TransactionTemplate(transactionManager).executeWithoutResult(status ->
                families.findById(family.getId()).orElseThrow().setDefaultGoogleCalendarId(hub.getId()));
        family = families.findById(family.getId()).orElseThrow();

        series = writes.create(family, new CalendarWriteRequest(null, List.of(maya.getId()), "Class", null, null,
                false, Instant.parse("2026-11-07T00:00:00Z"), Instant.parse("2026-11-07T01:00:00Z"), null, null,
                "America/Los_Angeles", List.of("RRULE:FREQ=WEEKLY;BYDAY=FR"), false, null));
    }

    private static CalendarWriteRequest change(String title, String start, String end, UUID member) {
        return new CalendarWriteRequest(null, List.of(member), title, null, null, false, Instant.parse(start),
                Instant.parse(end), null, null, null, null, false, null);
    }

    private List<String> november() {
        return query.eventsFor(family, LocalDate.parse("2026-11-01"), LocalDate.parse("2026-11-30"), null).stream()
                .map(event -> event.title() + "@" + event.start())
                .toList();
    }

    private List<Event> inGoogle() {
        return google.events(hub.getGoogleCalendarId());
    }

    @Test
    void oneDateChangesOnItsOwnAndTheSeriesStaysAsItWas() {
        CalendarEventResponse changed = writes.updateOccurrence(family, series.id(), NOV_13,
                change("Class (moved)", "2026-11-14T18:00:00Z", "2026-11-14T19:00:00Z", maya.getId()));

        assertThat(changed.recurring()).isTrue();
        assertThat(changed.recurringEventId()).isEqualTo(series.id());
        assertThat(changed.originalStart()).isEqualTo(NOV_13);
        assertThat(changed.start()).isEqualTo("2026-11-14T18:00:00Z");
        assertThat(inGoogle()).extracting(Event::getSummary).containsExactlyInAnyOrder("Class", "Class (moved)");
        assertThat(november()).containsExactly("Class@2026-11-07T00:00:00Z", "Class (moved)@2026-11-14T18:00:00Z",
                "Class@2026-11-21T00:00:00Z", "Class@2026-11-28T00:00:00Z");
    }

    @Test
    void oneDateIsCancelledOnItsOwn() {
        writes.deleteOccurrence(family, series.id(), NOV_13);

        assertThat(november()).containsExactly("Class@2026-11-07T00:00:00Z", "Class@2026-11-21T00:00:00Z",
                "Class@2026-11-28T00:00:00Z");
    }

    @Test
    void thisAndFollowingEndsTheOldSeriesBeforeTheDateAndStartsANewOneWithTheChange() {
        CalendarEventResponse moved = writes.updateOccurrence(family, series.id(), "20261128T000000Z",
                change("Class (one off)", "2026-11-28T02:00:00Z", "2026-11-28T03:00:00Z", maya.getId()));

        CalendarEventResponse following = writes.updateFollowing(family, series.id(), NOV_20,
                change("Class (new room)", "2026-11-21T00:00:00Z", "2026-11-21T01:00:00Z", maya.getId()));

        assertThat(following.recurring()).isTrue();
        assertThat(following.id()).isNotEqualTo(series.id());
        assertThat(following.recurrence()).containsExactly("RRULE:FREQ=WEEKLY;BYDAY=FR");
        Event old = google.getEvent(account, hub.getGoogleCalendarId(), series.googleEventId());
        assertThat(old.getRecurrence()).containsExactly("RRULE:FREQ=WEEKLY;BYDAY=FR;UNTIL=20261120T235959Z");
        // The date changed on its own before the split now follows the new series, as in Google's app.
        assertThat(inGoogle()).noneMatch(event -> moved.googleEventId().equals(event.getId()));
        assertThat(november()).containsExactly("Class@2026-11-07T00:00:00Z", "Class@2026-11-14T00:00:00Z",
                "Class (new room)@2026-11-21T00:00:00Z", "Class (new room)@2026-11-28T00:00:00Z");
    }

    @Test
    void deletingThisAndFollowingEndsTheSeries() {
        writes.deleteFollowing(family, series.id(), NOV_20);

        assertThat(november()).containsExactly("Class@2026-11-07T00:00:00Z", "Class@2026-11-14T00:00:00Z");
        assertThat(inGoogle()).singleElement().satisfies(event -> assertThat(event.getRecurrence())
                .containsExactly("RRULE:FREQ=WEEKLY;BYDAY=FR;UNTIL=20261120T235959Z"));
    }

    @Test
    void thisAndFollowingFromTheFirstDateChangesTheWholeSeries() {
        CalendarEventResponse whole = writes.updateFollowing(family, series.id(), "20261107T000000Z",
                change("Class (all)", "2026-11-07T00:00:00Z", "2026-11-07T01:00:00Z", maya.getId()));

        assertThat(whole.id()).isEqualTo(series.id());
        assertThat(inGoogle()).singleElement().satisfies(event -> assertThat(event.getSummary()).isEqualTo("Class (all)"));
    }

    @Test
    void aDateOfSomethingThatIsNotASeriesIsRefused() {
        CalendarEventResponse single = writes.create(family, change("Swim", "2026-12-03T17:00:00Z",
                "2026-12-03T18:00:00Z", maya.getId()));

        assertThatThrownBy(() -> writes.deleteOccurrence(family, single.id(), "20261203T170000Z"))
                .isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> writes.updateOccurrence(family, series.id(), "not-a-date",
                change("X", "2026-11-14T18:00:00Z", "2026-11-14T19:00:00Z", maya.getId())))
                .isInstanceOf(BadRequestException.class);
    }
}
