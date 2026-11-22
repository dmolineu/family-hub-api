package com.familyhub.demo.integration;

import com.familyhub.demo.config.TestcontainersConfig;
import com.familyhub.demo.dto.calendar.CalendarEventResponse;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyColor;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.GoogleAccessRole;
import com.familyhub.demo.model.GoogleCalendar;
import com.familyhub.demo.model.GoogleCalendarAccess;
import com.familyhub.demo.model.GoogleEvent;
import com.familyhub.demo.model.GoogleEventStatus;
import com.familyhub.demo.model.GoogleOAuthToken;
import com.familyhub.demo.repository.FamilyMemberRepository;
import com.familyhub.demo.repository.FamilyRepository;
import com.familyhub.demo.repository.GoogleCalendarAccessRepository;
import com.familyhub.demo.repository.GoogleCalendarRepository;
import com.familyhub.demo.repository.GoogleEventRepository;
import com.familyhub.demo.repository.GoogleOAuthTokenRepository;
import com.familyhub.demo.service.CalendarQueryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Reading the calendar from the cache (Phase 4): singles in the window, series expanded
 * in the family's zone, one copy of an event two calendars hold (the organiser's), only
 * enabled calendars, the member filter, and {@code canEdit} from the account's role. The
 * wire format: timed starts and ends as ISO instants, all-day ones as dates with
 * Google's exclusive end, and an occurrence named by its series and original start.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class CalendarQueryIntegrationTest {

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

    private Family family;
    private FamilyMember dad;
    private FamilyMember maya;
    private GoogleCalendar hub;
    private GoogleCalendar shared;

    @BeforeEach
    void setUp() {
        family = family("query");
        dad = member(family, "Dad");
        maya = member(family, "Maya");
        GoogleOAuthToken account = new GoogleOAuthToken();
        account.setMember(dad);
        account.setAccessToken("enc-access");
        account.setRefreshToken("enc-refresh");
        account.setTokenExpiry(Instant.now().plusSeconds(3600));
        account.setScope("calendar.events");
        account = tokens.save(account);
        hub = calendar("hub", "#9fc6e7", dad);
        shared = calendar("shared", "#ff887c", dad);
        access.save(new GoogleCalendarAccess(hub, account, GoogleAccessRole.OWNER));
        access.save(new GoogleCalendarAccess(shared, account, GoogleAccessRole.READER));
    }

    private Family family(String name) {
        Family created = new Family();
        created.setName(name);
        created.setUsername(name + "-" + UUID.randomUUID());
        created.setPasswordHash("$2a$10$dummyhashfortesting");
        created.setTimezone("America/Los_Angeles");
        return families.save(created);
    }

    private FamilyMember member(Family owner, String name) {
        FamilyMember member = new FamilyMember();
        member.setFamily(owner);
        member.setName(name);
        member.setColor(FamilyColor.CORAL);
        return members.save(member);
    }

    private GoogleCalendar calendar(String name, String color, FamilyMember lane) {
        GoogleCalendar calendar = new GoogleCalendar();
        calendar.setFamily(family);
        calendar.setGoogleCalendarId(name + "-" + UUID.randomUUID() + "@group.calendar.google.com");
        calendar.setSummary(name);
        calendar.setColor(color);
        calendar.setTimeZone("America/Los_Angeles");
        calendar.getLaneMembers().add(lane);
        return calendars.save(calendar);
    }

    private GoogleEvent timed(GoogleCalendar calendar, String googleId, String title, String start, String end,
                              FamilyMember... owners) {
        GoogleEvent event = new GoogleEvent();
        event.setCalendar(calendar);
        event.setGoogleEventId(googleId);
        event.setTitle(title);
        event.setEtag("\"1\"");
        event.setStartAt(Instant.parse(start));
        event.setEndAt(Instant.parse(end));
        event.setMemberIds(java.util.Arrays.stream(owners).map(FamilyMember::getId).toArray(UUID[]::new));
        event.setRaw("{}");
        return event;
    }

    private List<CalendarEventResponse> december(UUID memberId) {
        return query.eventsFor(family, LocalDate.parse("2026-12-01"), LocalDate.parse("2026-12-31"), memberId);
    }

    @Test
    void singlesAndExpandedSeriesComeBackInOrderWithTheirWireFormat() {
        GoogleEvent swim = events.save(timed(hub, "swim", "Swim", "2026-12-03T17:00:00Z", "2026-12-03T18:00:00Z", maya));
        GoogleEvent trip = timed(hub, "trip", "Trip", "2026-12-01T00:00:00Z", "2026-12-01T00:00:00Z", dad);
        trip.setAllDay(true);
        trip.setStartAt(null);
        trip.setEndAt(null);
        trip.setStartDate(LocalDate.parse("2026-12-10"));
        trip.setEndDate(LocalDate.parse("2026-12-13"));
        events.save(trip);
        GoogleEvent weekly = timed(hub, "weekly", "Class", "2026-12-04T00:00:00Z", "2026-12-04T01:00:00Z", maya);
        weekly.setRecurrence(new String[]{"RRULE:FREQ=WEEKLY;COUNT=2"});
        weekly = events.save(weekly);
        events.save(timed(hub, "november", "Old", "2026-11-03T17:00:00Z", "2026-11-03T18:00:00Z", dad));

        List<CalendarEventResponse> found = december(null);

        assertThat(found).extracting(CalendarEventResponse::title)
                .containsExactly("Swim", "Class", "Trip", "Class");
        CalendarEventResponse first = found.getFirst();
        assertThat(first.id()).isEqualTo(swim.getId());
        assertThat(first.occurrenceId()).isEqualTo(swim.getId().toString());
        assertThat(first.start()).isEqualTo("2026-12-03T17:00:00Z");
        assertThat(first.end()).isEqualTo("2026-12-03T18:00:00Z");
        assertThat(first.allDay()).isFalse();
        assertThat(first.recurring()).isFalse();
        assertThat(first.calendarId()).isEqualTo(hub.getId());
        assertThat(first.calendarColor()).isEqualTo("#9fc6e7");
        assertThat(first.memberIds()).containsExactly(maya.getId());
        assertThat(first.timeZone()).isEqualTo("America/Los_Angeles");
        assertThat(first.status()).isEqualTo("confirmed");
        assertThat(first.canEdit()).isTrue();
        assertThat(first.etag()).isEqualTo("\"1\"");

        CalendarEventResponse occurrence = found.get(1);
        assertThat(occurrence.id()).isEqualTo(weekly.getId());
        assertThat(occurrence.recurring()).isTrue();
        assertThat(occurrence.recurringEventId()).isEqualTo(weekly.getId());
        assertThat(occurrence.originalStart()).isEqualTo("20261204T000000Z");
        assertThat(occurrence.occurrenceId()).isEqualTo(weekly.getId() + ":20261204T000000Z");
        assertThat(occurrence.recurrence()).containsExactly("RRULE:FREQ=WEEKLY;COUNT=2");

        CalendarEventResponse allDay = found.get(2);
        assertThat(allDay.allDay()).isTrue();
        assertThat(allDay.start()).isEqualTo("2026-12-10");
        assertThat(allDay.end()).isEqualTo("2026-12-13");
    }

    @Test
    void anEventOnTwoCalendarsShowsOnceAsTheOrganisersCopyAndReadOnlyCalendarsCannotBeEdited() {
        GoogleEvent invite = timed(shared, "party", "Party", "2026-12-05T01:00:00Z", "2026-12-05T03:00:00Z", dad);
        invite.setIcalUid("party@google.com");
        events.save(invite);
        GoogleEvent organiser = timed(hub, "party", "Party", "2026-12-05T01:00:00Z", "2026-12-05T03:00:00Z", dad);
        organiser.setIcalUid("party@google.com");
        organiser.setOrganizerSelf(true);
        organiser = events.save(organiser);
        events.save(timed(shared, "readonly", "Recital", "2026-12-06T01:00:00Z", "2026-12-06T02:00:00Z", dad));

        List<CalendarEventResponse> found = december(null);

        assertThat(found).extracting(CalendarEventResponse::title).containsExactly("Party", "Recital");
        assertThat(found.get(0).id()).isEqualTo(organiser.getId());
        assertThat(found.get(0).canEdit()).isTrue();
        assertThat(found.get(1).canEdit()).isFalse();
    }

    @Test
    void cancelledOccurrencesDisabledCalendarsAndOtherMembersAreLeftOut() {
        GoogleEvent weekly = timed(hub, "weekly", "Class", "2026-12-04T00:00:00Z", "2026-12-04T01:00:00Z", maya);
        weekly.setRecurrence(new String[]{"RRULE:FREQ=WEEKLY;COUNT=3"});
        events.save(weekly);
        GoogleEvent cancelled = new GoogleEvent();
        cancelled.setCalendar(hub);
        cancelled.setGoogleEventId("weekly_20261211T000000Z");
        cancelled.setRecurringEventId("weekly");
        cancelled.setStatus(GoogleEventStatus.CANCELLED);
        cancelled.setOriginalStartAt(Instant.parse("2026-12-11T00:00:00Z"));
        cancelled.setRaw("{}");
        events.save(cancelled);
        events.save(timed(hub, "dads", "Dad's", "2026-12-07T17:00:00Z", "2026-12-07T18:00:00Z", dad));
        GoogleCalendar off = calendar("off", "#000000", maya);
        off.setEnabled(false);
        calendars.save(off);
        events.save(timed(off, "hidden", "Hidden", "2026-12-08T17:00:00Z", "2026-12-08T18:00:00Z", maya));

        assertThat(december(maya.getId())).extracting(CalendarEventResponse::originalStart)
                .containsExactly("20261204T000000Z", "20261218T000000Z");
        assertThat(december(null)).extracting(CalendarEventResponse::title).containsExactly("Class", "Dad's", "Class");
    }

    @Test
    void aWindowOverAYearAndAnotherFamilysMemberAreRefused() {
        assertThatThrownBy(() -> query.eventsFor(family, LocalDate.parse("2026-01-01"),
                LocalDate.parse("2027-01-03"), null)).isInstanceOf(BadRequestException.class);
        FamilyMember stranger = member(family("other"), "Stranger");
        assertThatThrownBy(() -> december(stranger.getId())).isInstanceOf(AccessDeniedException.class);
    }
}
