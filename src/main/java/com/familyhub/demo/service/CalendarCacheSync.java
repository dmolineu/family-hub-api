package com.familyhub.demo.service;

import com.familyhub.demo.config.GoogleCalendarProperties;
import com.familyhub.demo.google.GoogleCalendarException;
import com.familyhub.demo.google.GoogleCalendarGateway;
import com.familyhub.demo.google.GoogleCalendarGateway.EventPage;
import com.familyhub.demo.google.GoogleCalendarGateway.ListRequest;
import com.familyhub.demo.google.GoogleEventCacheMapper;
import com.familyhub.demo.google.MemberAttribution;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.GoogleCalendar;
import com.familyhub.demo.model.GoogleEvent;
import com.familyhub.demo.model.GoogleOAuthToken;
import com.familyhub.demo.repository.FamilyMemberRepository;
import com.familyhub.demo.repository.GoogleCalendarRepository;
import com.familyhub.demo.repository.GoogleEventRepository;
import com.familyhub.demo.repository.GoogleOAuthTokenRepository;
import com.google.api.services.calendar.model.Event;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Keeps the Google event cache in step with Google (Phase 4). Each calendar is synced on
 * its own: a full sync the first time and whenever Google drops the sync token (in the
 * same run), incremental syncs after that. Google is read outside any transaction; what
 * it returned is then written in one transaction, so a failed run changes nothing.
 * A calendar is never synced twice at once; a request during a run makes it run once
 * more afterwards. Rate limits back the calendar off (1 minute, doubling to 30); an
 * account Google refuses is marked and its calendars wait until it signs in again.
 */
@Slf4j
@Service
public class CalendarCacheSync {

    /** What a sync of one calendar did. */
    public enum Outcome {
        /** The cache now matches Google. */
        SYNCED,
        /** A sync of this calendar was running; it runs once more when that ends. */
        BUSY,
        /** Backing off after Google's rate limit. */
        WAITING,
        /** The account that polls the calendar must sign in again. */
        NEEDS_REAUTH,
        /** The calendar is switched off, or gone. */
        DISABLED,
        /** No signed-in account polls the calendar. */
        NO_ACCOUNT,
        /** Google failed; the error is on the calendar and the next run tries again. */
        FAILED
    }

    private static final Duration FIRST_BACKOFF = Duration.ofMinutes(1);
    private static final Duration MAX_BACKOFF = Duration.ofMinutes(30);
    private static final int MAX_ERROR_LENGTH = 1000;

    private record Backoff(Instant until, Duration length) {
    }

    private record Fetched(List<Event> items, int pages, String syncToken) {
    }

    private record Counts(int upserted, int deleted) {
    }

    /** Whether a calendar is being synced, and whether it was asked for again meanwhile. */
    private static final class RunState {
        private boolean running;
        private boolean rerun;
    }

    private final GoogleCalendarGateway gateway;
    private final GoogleCalendarRepository calendars;
    private final GoogleEventRepository events;
    private final FamilyMemberRepository members;
    private final GoogleOAuthTokenRepository tokens;
    private final GoogleEventCacheMapper mapper;
    private final MemberAttribution attribution;
    private final GoogleCalendarProperties properties;
    private final Clock clock;
    private final TransactionTemplate transactions;
    private final Map<UUID, RunState> runs = new ConcurrentHashMap<>();
    private final Map<UUID, Backoff> backoffs = new ConcurrentHashMap<>();

    /**
     * Creates the sync.
     *
     * @param gateway            Google
     * @param calendars          cached calendars
     * @param events             cached events
     * @param members            family members, for attribution
     * @param tokens             signed-in accounts
     * @param mapper             Google event to cache row
     * @param attribution        whose lanes an event goes in
     * @param properties         how far back a full sync reaches
     * @param clock              the time, for backoff and timestamps
     * @param transactionManager runs the write of each sync in one transaction
     */
    public CalendarCacheSync(GoogleCalendarGateway gateway, GoogleCalendarRepository calendars,
                             GoogleEventRepository events, FamilyMemberRepository members,
                             GoogleOAuthTokenRepository tokens, GoogleEventCacheMapper mapper,
                             MemberAttribution attribution, GoogleCalendarProperties properties, Clock clock,
                             PlatformTransactionManager transactionManager) {
        this.gateway = gateway;
        this.calendars = calendars;
        this.events = events;
        this.members = members;
        this.tokens = tokens;
        this.mapper = mapper;
        this.attribution = attribution;
        this.properties = properties;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Syncs every enabled calendar in turn; one calendar's failure never stops the others. */
    public void syncAll() {
        for (UUID calendarId : calendars.findEnabledIds()) {
            try {
                syncCalendar(calendarId);
            } catch (RuntimeException e) {
                log.error("Calendar sync failed, calendarId={}", calendarId, e);
            }
        }
    }

    /**
     * Syncs one calendar, unless it is already being synced (then it is synced once more
     * when that run ends).
     *
     * @param calendarId the cached calendar's id
     * @return what this call did
     */
    public Outcome syncCalendar(UUID calendarId) {
        RunState state = runs.computeIfAbsent(calendarId, id -> new RunState());
        synchronized (state) {
            if (state.running) {
                state.rerun = true;
                return Outcome.BUSY;
            }
            state.running = true;
        }
        try {
            while (true) {
                Outcome outcome = syncOnce(calendarId);
                synchronized (state) {
                    if (!state.rerun) {
                        state.running = false;
                        return outcome;
                    }
                    state.rerun = false;
                }
            }
        } catch (RuntimeException e) {
            synchronized (state) {
                state.running = false;
                state.rerun = false;
            }
            throw e;
        }
    }

    private Outcome syncOnce(UUID calendarId) {
        Optional<GoogleCalendar> found = calendars.findForSync(calendarId);
        if (found.isEmpty() || !found.get().isEnabled()) {
            return Outcome.DISABLED;
        }
        GoogleCalendar calendar = found.get();
        GoogleOAuthToken account = calendar.getPollingToken();
        if (account == null) {
            recordError(calendarId, "No signed-in account polls this calendar");
            return Outcome.NO_ACCOUNT;
        }
        if (account.isNeedsReauth()) {
            return Outcome.NEEDS_REAUTH;
        }
        Backoff backoff = backoffs.get(calendarId);
        if (backoff != null && clock.instant().isBefore(backoff.until())) {
            return Outcome.WAITING;
        }

        long started = System.nanoTime();
        boolean full = calendar.isFullSyncRequired() || calendar.getSyncToken() == null;
        Fetched fetched;
        try {
            try {
                fetched = fetch(account, calendar, full);
            } catch (GoogleCalendarException e) {
                if (full || e.getKind() != GoogleCalendarException.Kind.SYNC_TOKEN_EXPIRED) {
                    throw e;
                }
                log.info("Google dropped the sync token, calendarId={}: full sync", calendarId);
                full = true;
                fetched = fetch(account, calendar, true);
            }
        } catch (GoogleCalendarException e) {
            return failed(calendarId, account, e);
        }
        boolean fullSync = full;
        Fetched result = fetched;
        Counts counts = transactions.execute(status -> persist(calendarId, result, fullSync));
        backoffs.remove(calendarId);
        log.info("Calendar sync calendarId={} mode={} pages={} items={} upserted={} deleted={} ms={}",
                calendarId, fullSync ? "full" : "incremental", result.pages(), result.items().size(),
                counts.upserted(), counts.deleted(), (System.nanoTime() - started) / 1_000_000);
        return Outcome.SYNCED;
    }

    private Fetched fetch(GoogleOAuthToken account, GoogleCalendar calendar, boolean full) {
        List<Event> items = new ArrayList<>();
        Instant timeMin = clock.instant().minus(properties.getFullSyncLookback());
        int pages = 0;
        String pageToken = null;
        String syncToken = null;
        do {
            EventPage page = gateway.listEvents(account, calendar.getGoogleCalendarId(), full
                    ? ListRequest.fullSync(timeMin, pageToken)
                    : ListRequest.incremental(calendar.getSyncToken(), pageToken));
            pages++;
            items.addAll(page.items());
            pageToken = page.nextPageToken();
            if (pageToken == null) {
                syncToken = page.nextSyncToken();
            }
        } while (pageToken != null);
        return new Fetched(items, pages, syncToken);
    }

    private Counts persist(UUID calendarId, Fetched fetched, boolean full) {
        GoogleCalendar calendar = calendars.findForSync(calendarId).orElseThrow();
        List<FamilyMember> family = members.findByFamily(calendar.getFamily());
        Map<String, GoogleEvent> cached = new HashMap<>();
        for (GoogleEvent row : events.findAllByCalendarId(calendarId)) {
            cached.put(row.getGoogleEventId(), row);
        }
        Set<String> seen = new HashSet<>();
        Set<String> deletedSeries = new HashSet<>();
        int upserted = 0;
        int deleted = 0;
        for (Event item : fetched.items()) {
            boolean cancelled = "cancelled".equals(item.getStatus());
            if (cancelled && item.getRecurringEventId() == null) {
                // A deleted single, or a deleted series with every occurrence of it.
                deleted += deleteWithOccurrences(item.getId(), cached);
                deletedSeries.add(item.getId());
                continue;
            }
            if (item.getRecurringEventId() != null && deletedSeries.contains(item.getRecurringEventId())) {
                continue;
            }
            seen.add(item.getId());
            GoogleEvent row = cached.computeIfAbsent(item.getId(), id -> new GoogleEvent());
            UUID[] memberIds = attribution.membersOf(item, calendar, family).toArray(UUID[]::new);
            events.save(mapper.toCache(item, calendar, memberIds, row));
            upserted++;
        }
        if (full) {
            // Whatever Google did not return in a full sync is gone from Google.
            for (GoogleEvent row : List.copyOf(cached.values())) {
                if (!seen.contains(row.getGoogleEventId())) {
                    events.delete(row);
                    cached.remove(row.getGoogleEventId());
                    deleted++;
                }
            }
        }
        calendar.setSyncToken(fetched.syncToken());
        calendar.setFullSyncRequired(false);
        calendar.setLastSyncedAt(clock.instant());
        calendar.setLastSyncError(null);
        return new Counts(upserted, deleted);
    }

    private int deleteWithOccurrences(String googleEventId, Map<String, GoogleEvent> cached) {
        int deleted = 0;
        Iterator<GoogleEvent> rows = cached.values().iterator();
        while (rows.hasNext()) {
            GoogleEvent row = rows.next();
            if (googleEventId.equals(row.getGoogleEventId()) || googleEventId.equals(row.getRecurringEventId())) {
                if (row.getId() != null) {
                    events.delete(row);
                }
                rows.remove();
                deleted++;
            }
        }
        return deleted;
    }

    private Outcome failed(UUID calendarId, GoogleOAuthToken account, GoogleCalendarException failure) {
        switch (failure.getKind()) {
            case REAUTH_REQUIRED -> {
                tokens.markNeedsReauth(account.getId());
                recordError(calendarId, "Google needs this account to sign in again");
                log.warn("Google refused the account's sign-in, tokenId={}, calendarId={}: {}",
                        account.getId(), calendarId, failure.getMessage());
                return Outcome.NEEDS_REAUTH;
            }
            case RATE_LIMITED -> {
                Duration length = backOff(calendarId, failure.getRetryAfter());
                recordError(calendarId, failure.getMessage());
                log.warn("Google rate-limited the calendar sync, calendarId={}: waiting {}", calendarId, length);
                return Outcome.FAILED;
            }
            default -> {
                recordError(calendarId, failure.getMessage());
                log.warn("Calendar sync failed, calendarId={}, kind={}: {}", calendarId, failure.getKind(),
                        failure.getMessage());
                return Outcome.FAILED;
            }
        }
    }

    private Duration backOff(UUID calendarId, Optional<Duration> retryAfter) {
        Backoff previous = backoffs.get(calendarId);
        Duration length = previous == null ? FIRST_BACKOFF : previous.length().multipliedBy(2);
        if (length.compareTo(MAX_BACKOFF) > 0) {
            length = MAX_BACKOFF;
        }
        if (retryAfter.isPresent() && retryAfter.get().compareTo(length) > 0) {
            length = retryAfter.get();
        }
        backoffs.put(calendarId, new Backoff(clock.instant().plus(length), length));
        return length;
    }

    private void recordError(UUID calendarId, String message) {
        String error = message == null ? "Unknown error" : message;
        String truncated = error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error;
        transactions.executeWithoutResult(status ->
                calendars.findById(calendarId).ifPresent(calendar -> calendar.setLastSyncError(truncated)));
    }
}
