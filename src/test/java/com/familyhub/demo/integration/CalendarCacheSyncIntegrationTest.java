package com.familyhub.demo.integration;

import com.familyhub.demo.config.TestcontainersConfig;
import com.familyhub.demo.google.FakeGoogleCalendarGateway;
import com.familyhub.demo.google.GoogleCalendarException;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyColor;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.GoogleAccessRole;
import com.familyhub.demo.model.GoogleCalendar;
import com.familyhub.demo.model.GoogleEvent;
import com.familyhub.demo.model.GoogleEventStatus;
import com.familyhub.demo.model.GoogleOAuthToken;
import com.familyhub.demo.repository.FamilyMemberRepository;
import com.familyhub.demo.repository.FamilyRepository;
import com.familyhub.demo.repository.GoogleCalendarRepository;
import com.familyhub.demo.repository.GoogleEventRepository;
import com.familyhub.demo.repository.GoogleOAuthTokenRepository;
import com.familyhub.demo.service.CalendarCacheSync;
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

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The calendar cache sync (Phase 4) against the in-memory Google: a full sync over
 * several pages, incremental changes that keep row ids, cancelled occurrences kept to
 * hide a date, a deleted series taking its occurrences, an expired sync token resynced
 * in the same run, backoff on rate limits, accounts that must sign in again, and a
 * second trigger during a run.
 */
@SpringBootTest
@Import({TestcontainersConfig.class, CalendarCacheSyncIntegrationTest.Config.class})
@ActiveProfiles("test")
class CalendarCacheSyncIntegrationTest {

    /** The fake Google, able to hold a list call open until the test lets it go. */
    static final class HoldingFakeGoogle extends FakeGoogleCalendarGateway {
        final AtomicInteger listCalls = new AtomicInteger();
        volatile CountDownLatch hold;

        @Override
        public EventPage listEvents(GoogleOAuthToken account, String calendarId, ListRequest request) {
            listCalls.incrementAndGet();
            CountDownLatch latch = hold;
            if (latch != null) {
                try {
                    latch.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return super.listEvents(account, calendarId, request);
        }
    }

    /** A clock the test moves forward by hand. */
    static final class MutableClock extends Clock {
        private volatile Instant now = Instant.now();

        void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return Clock.fixed(now, zone);
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        HoldingFakeGoogle holdingFakeGoogle() {
            return new HoldingFakeGoogle();
        }

        @Bean
        @Primary
        MutableClock mutableClock() {
            return new MutableClock();
        }
    }

    @Autowired
    HoldingFakeGoogle google;

    @Autowired
    MutableClock clock;

    @Autowired
    CalendarCacheSync sync;

    @Autowired
    FamilyRepository families;

    @Autowired
    FamilyMemberRepository members;

    @Autowired
    GoogleOAuthTokenRepository tokens;

    @Autowired
    GoogleCalendarRepository calendars;

    @Autowired
    GoogleEventRepository events;

    private GoogleOAuthToken account;
    private FamilyMember dad;
    private GoogleCalendar hub;
    private String hubId;

    @BeforeEach
    void setUp() {
        google.setPageSize(250);
        google.hold = null;
        Family family = new Family();
        family.setName("Sync family");
        family.setUsername("cache-sync-" + UUID.randomUUID());
        family.setPasswordHash("$2a$10$dummyhashfortesting");
        family = families.save(family);
        dad = new FamilyMember();
        dad.setFamily(family);
        dad.setName("Dad");
        dad.setColor(FamilyColor.CORAL);
        dad = members.save(dad);

        GoogleOAuthToken token = new GoogleOAuthToken();
        token.setMember(dad);
        token.setAccessToken("enc-access");
        token.setRefreshToken("enc-refresh");
        token.setTokenExpiry(Instant.now().plusSeconds(3600));
        token.setScope("calendar.events");
        account = tokens.save(token);

        hubId = "hub-" + UUID.randomUUID() + "@group.calendar.google.com";
        google.addCalendar(hubId, "Hub", "America/Los_Angeles");
        google.grant(account, hubId, GoogleAccessRole.OWNER);
        GoogleCalendar calendar = new GoogleCalendar();
        calendar.setFamily(family);
        calendar.setGoogleCalendarId(hubId);
        calendar.setSummary("Hub");
        calendar.setPollingToken(account);
        calendar.getLaneMembers().add(dad);
        hub = calendars.save(calendar);
    }

    private static EventDateTime at(String instant) {
        return new EventDateTime().setDateTime(new DateTime(instant)).setTimeZone("America/Los_Angeles");
    }

    private Event insert(String title, String start, String end) {
        return google.insertEvent(account, hubId, new Event().setSummary(title).setStart(at(start)).setEnd(at(end)),
                false);
    }

    private List<GoogleEvent> rows() {
        return events.findAllByCalendarId(hub.getId());
    }

    private List<String> titles() {
        return rows().stream().map(GoogleEvent::getTitle).sorted().toList();
    }

    private GoogleEvent row(String googleId) {
        return rows().stream().filter(e -> e.getGoogleEventId().equals(googleId)).findFirst().orElseThrow();
    }

    private GoogleCalendar reloaded() {
        return calendars.findById(hub.getId()).orElseThrow();
    }

    @Test
    void aFullSyncReadsEveryPageAttributesTheLaneAndKeepsTheLastPagesToken() {
        google.setPageSize(1);
        insert("One", "2026-12-03T17:00:00Z", "2026-12-03T18:00:00Z");
        insert("Two", "2026-12-04T17:00:00Z", "2026-12-04T18:00:00Z");
        insert("Three", "2026-12-05T17:00:00Z", "2026-12-05T18:00:00Z");

        assertThat(sync.syncCalendar(hub.getId())).isEqualTo(CalendarCacheSync.Outcome.SYNCED);

        assertThat(titles()).containsExactly("One", "Three", "Two");
        assertThat(rows()).allSatisfy(row -> assertThat(row.getMemberIds()).containsExactly(dad.getId()));
        GoogleCalendar after = reloaded();
        assertThat(after.getSyncToken()).isNotBlank();
        assertThat(after.isFullSyncRequired()).isFalse();
        assertThat(after.getLastSyncedAt()).isNotNull();
        assertThat(after.getLastSyncError()).isNull();
    }

    @Test
    void anIncrementalSyncUpdatesInPlaceDropsDeletionsAndKeepsCancelledOccurrences() {
        Event keep = insert("Keep", "2026-12-03T17:00:00Z", "2026-12-03T18:00:00Z");
        Event drop = insert("Drop", "2026-12-04T17:00:00Z", "2026-12-04T18:00:00Z");
        Event weekly = google.insertEvent(account, hubId, new Event().setSummary("Class")
                .setStart(at("2026-11-06T17:00:00Z")).setEnd(at("2026-11-06T18:00:00Z"))
                .setRecurrence(List.of("RRULE:FREQ=WEEKLY;BYDAY=FR")), false);
        sync.syncCalendar(hub.getId());
        UUID keepRowId = row(keep.getId()).getId();

        google.updateEvent(account, hubId, keep.getId(), keep.clone().setSummary("Kept"), keep.getEtag(), false);
        google.deleteEvent(account, hubId, drop.getId(), drop.getEtag());
        Event second = google.getInstance(account, hubId, weekly.getId(), at("2026-11-13T17:00:00Z"));
        google.deleteEvent(account, hubId, second.getId(), second.getEtag());

        assertThat(sync.syncCalendar(hub.getId())).isEqualTo(CalendarCacheSync.Outcome.SYNCED);
        assertThat(row(keep.getId()).getId()).isEqualTo(keepRowId);
        assertThat(row(keep.getId()).getTitle()).isEqualTo("Kept");
        assertThat(rows()).noneMatch(e -> e.getGoogleEventId().equals(drop.getId()));
        GoogleEvent cancelled = row(second.getId());
        assertThat(cancelled.getStatus()).isEqualTo(GoogleEventStatus.CANCELLED);
        assertThat(cancelled.getRecurringEventId()).isEqualTo(weekly.getId());
        assertThat(cancelled.getOriginalStartAt()).isEqualTo(Instant.parse("2026-11-13T17:00:00Z"));

        String etag = google.getEvent(account, hubId, weekly.getId()).getEtag();
        google.deleteEvent(account, hubId, weekly.getId(), etag);
        sync.syncCalendar(hub.getId());
        assertThat(rows()).extracting(GoogleEvent::getGoogleEventId).containsExactly(keep.getId());
    }

    @Test
    void anExpiredSyncTokenIsReplacedByAFullSyncInTheSameRunWhichDropsWhatGoogleNoLongerHas() {
        insert("A", "2026-12-03T17:00:00Z", "2026-12-03T18:00:00Z");
        sync.syncCalendar(hub.getId());
        String oldToken = reloaded().getSyncToken();
        GoogleEvent stale = new GoogleEvent();
        stale.setCalendar(hub);
        stale.setGoogleEventId("stale");
        stale.setTitle("Stale");
        stale.setStartAt(Instant.parse("2026-12-03T17:00:00Z"));
        stale.setEndAt(Instant.parse("2026-12-03T18:00:00Z"));
        stale.setRaw("{}");
        events.save(stale);

        google.expireSyncTokens();
        insert("B", "2026-12-04T17:00:00Z", "2026-12-04T18:00:00Z");

        assertThat(sync.syncCalendar(hub.getId())).isEqualTo(CalendarCacheSync.Outcome.SYNCED);
        assertThat(titles()).containsExactly("A", "B");
        assertThat(reloaded().getSyncToken()).isNotEqualTo(oldToken);
    }

    @Test
    void aRateLimitBacksTheCalendarOffAndLeavesItsTokenAlone() {
        sync.syncCalendar(hub.getId());
        String token = reloaded().getSyncToken();

        google.failNextWith(GoogleCalendarException.Kind.RATE_LIMITED);
        assertThat(sync.syncCalendar(hub.getId())).isEqualTo(CalendarCacheSync.Outcome.FAILED);
        assertThat(reloaded().getSyncToken()).isEqualTo(token);
        assertThat(reloaded().getLastSyncError()).isNotBlank();

        insert("Later", "2026-12-03T17:00:00Z", "2026-12-03T18:00:00Z");
        assertThat(sync.syncCalendar(hub.getId())).isEqualTo(CalendarCacheSync.Outcome.WAITING);
        assertThat(titles()).isEmpty();

        clock.advance(Duration.ofMinutes(2));
        assertThat(sync.syncCalendar(hub.getId())).isEqualTo(CalendarCacheSync.Outcome.SYNCED);
        assertThat(titles()).containsExactly("Later");
        assertThat(reloaded().getLastSyncError()).isNull();
    }

    @Test
    void anAccountThatMustSignInAgainIsMarkedAndItsCalendarsWait() {
        google.failNextWith(GoogleCalendarException.Kind.REAUTH_REQUIRED);

        assertThat(sync.syncCalendar(hub.getId())).isEqualTo(CalendarCacheSync.Outcome.NEEDS_REAUTH);
        assertThat(tokens.findById(account.getId()).orElseThrow().isNeedsReauth()).isTrue();
        assertThat(reloaded().getLastSyncError()).isNotBlank();

        int calls = google.listCalls.get();
        insert("Unseen", "2026-12-03T17:00:00Z", "2026-12-03T18:00:00Z");
        assertThat(sync.syncCalendar(hub.getId())).isEqualTo(CalendarCacheSync.Outcome.NEEDS_REAUTH);
        assertThat(google.listCalls.get()).isEqualTo(calls);
        assertThat(titles()).isEmpty();
    }

    @Test
    void aTriggerDuringARunIsNotRunAlongsideItButOnceMoreAfterIt() throws Exception {
        CountDownLatch hold = new CountDownLatch(1);
        google.hold = hold;
        int before = google.listCalls.get();

        CompletableFuture<CalendarCacheSync.Outcome> first =
                CompletableFuture.supplyAsync(() -> sync.syncCalendar(hub.getId()));
        long deadline = System.currentTimeMillis() + 5_000;
        while (google.listCalls.get() == before && System.currentTimeMillis() < deadline) {
            Thread.sleep(10);
        }

        assertThat(sync.syncCalendar(hub.getId())).isEqualTo(CalendarCacheSync.Outcome.BUSY);
        google.hold = null;
        hold.countDown();
        assertThat(first.get(10, TimeUnit.SECONDS)).isEqualTo(CalendarCacheSync.Outcome.SYNCED);
        assertThat(google.listCalls.get() - before).isEqualTo(2);
    }

    @Test
    void disabledCalendarsAndCalendarsNobodyPollsAreLeftAlone() {
        GoogleCalendar calendar = reloaded();
        calendar.setEnabled(false);
        calendars.save(calendar);
        int calls = google.listCalls.get();
        assertThat(sync.syncCalendar(hub.getId())).isEqualTo(CalendarCacheSync.Outcome.DISABLED);

        calendar = reloaded();
        calendar.setEnabled(true);
        calendar.setPollingToken(null);
        calendars.save(calendar);
        assertThat(sync.syncCalendar(hub.getId())).isEqualTo(CalendarCacheSync.Outcome.NO_ACCOUNT);
        assertThat(reloaded().getLastSyncError()).isNotBlank();
        assertThat(google.listCalls.get()).isEqualTo(calls);
    }
}
