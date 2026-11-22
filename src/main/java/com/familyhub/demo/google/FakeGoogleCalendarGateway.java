package com.familyhub.demo.google;

import com.familyhub.demo.model.GoogleAccessRole;
import com.familyhub.demo.model.GoogleOAuthToken;
import com.google.api.client.json.gson.GsonFactory;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.CalendarListEntry;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * An in-memory Google Calendar for tests and the dev profile (Phase 4). It copies the
 * behaviour the hub depends on rather than all of Google: every change bumps a global
 * version, an event's etag is the version that last changed it, a sync token is
 * "changes after version N", deletions become cancelled tombstones carrying only what
 * Google sends for them, and one occurrence of a series becomes an instance with
 * Google's {@code <master>_<original start>} id once it is changed or cancelled.
 * Thread-safe: the dev profile's scheduler and requests share one instance.
 */
public class FakeGoogleCalendarGateway implements GoogleCalendarGateway {

    private static final DateTimeFormatter COMPACT_UTC =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);

    private record Stored(Event event, long version) {
        boolean cancelled() {
            return "cancelled".equals(event.getStatus());
        }
    }

    private record FakeCalendar(String id, String summary, String timeZone, Map<String, Stored> events) {
    }

    private final Map<String, FakeCalendar> calendars = new LinkedHashMap<>();
    private final Map<UUID, Map<String, GoogleAccessRole>> access = new HashMap<>();
    private final Map<UUID, String> primaries = new HashMap<>();
    // Occurrences handed out by getInstance but not changed yet, so not stored as instances.
    private final Map<String, Event> issuedInstances = new HashMap<>();
    private long version;
    private int generation;
    private int pageSize = 250;
    private int idCounter;
    private GoogleCalendarException.Kind failNext;
    private GoogleCalendarException.Kind failAll;

    /**
     * Creates an empty calendar.
     *
     * @param id       Google's calendar id
     * @param summary  its name
     * @param timeZone its time zone
     */
    public synchronized void addCalendar(String id, String summary, String timeZone) {
        calendars.putIfAbsent(id, new FakeCalendar(id, summary, timeZone, new LinkedHashMap<>()));
    }

    /**
     * Lets an account see, or write to, a calendar.
     *
     * @param account    the signed-in account
     * @param calendarId Google's calendar id
     * @param role       what the account may do there
     */
    public synchronized void grant(GoogleOAuthToken account, String calendarId, GoogleAccessRole role) {
        access.computeIfAbsent(account.getId(), id -> new LinkedHashMap<>()).put(calendarId, role);
    }

    /**
     * Makes a calendar the account's primary one, whose id is the account's address.
     *
     * @param account    the signed-in account
     * @param calendarId Google's calendar id
     */
    public synchronized void markPrimary(GoogleOAuthToken account, String calendarId) {
        primaries.put(account.getId(), calendarId);
    }

    /**
     * Takes an account's access to a calendar away, as unsharing it in Google does.
     *
     * @param account    the signed-in account
     * @param calendarId Google's calendar id
     */
    public synchronized void revoke(GoogleOAuthToken account, String calendarId) {
        access.getOrDefault(account.getId(), new LinkedHashMap<>()).remove(calendarId);
    }

    /**
     * Renames a calendar, as its owner can in Google.
     *
     * @param calendarId Google's calendar id
     * @param summary    the new name
     */
    public synchronized void rename(String calendarId, String summary) {
        FakeCalendar calendar = calendars.get(calendarId);
        calendars.put(calendarId, new FakeCalendar(calendar.id(), summary, calendar.timeZone(), calendar.events()));
    }

    /**
     * Sets how many events a page of events.list holds.
     *
     * @param size the page size
     */
    public synchronized void setPageSize(int size) {
        this.pageSize = size;
    }

    /** Makes every sync token issued so far invalid, as Google does from time to time. */
    public synchronized void expireSyncTokens() {
        generation++;
    }

    /**
     * Makes the next call, whatever it is, fail.
     *
     * @param kind how it fails
     */
    public synchronized void failNextWith(GoogleCalendarException.Kind kind) {
        this.failNext = kind;
    }

    /**
     * Makes every call fail until {@link #stopFailing()}, as when Google is down for a while.
     *
     * @param kind how they fail
     */
    public synchronized void failAllWith(GoogleCalendarException.Kind kind) {
        this.failAll = kind;
    }

    /** Ends {@link #failAllWith} and drops a queued {@link #failNextWith}. */
    public synchronized void stopFailing() {
        this.failAll = null;
        this.failNext = null;
    }

    /**
     * Changes an event as if someone edited it in Google's own app.
     *
     * @param calendarId Google's calendar id
     * @param eventId    Google's event id
     * @param edit       the change
     */
    public synchronized void editInGoogle(String calendarId, String eventId, Consumer<Event> edit) {
        FakeCalendar calendar = calendars.get(calendarId);
        Event event = copy(calendar.events().get(eventId).event());
        edit.accept(event);
        store(calendar, event);
    }

    /**
     * The calendar's events as Google holds them now, without tombstones.
     *
     * @param calendarId Google's calendar id
     * @return copies of the live events
     */
    public synchronized List<Event> events(String calendarId) {
        return calendars.get(calendarId).events().values().stream()
                .filter(stored -> !stored.cancelled())
                .map(stored -> copy(stored.event()))
                .toList();
    }

    @Override
    public synchronized EventPage listEvents(GoogleOAuthToken account, String calendarId, ListRequest request) {
        FakeCalendar calendar = readable(account, calendarId);
        List<Stored> matching;
        if (request.syncToken() != null) {
            long since = parseSyncToken(request.syncToken());
            matching = calendar.events().values().stream()
                    .filter(stored -> stored.version() > since)
                    .sorted(Comparator.comparingLong(Stored::version))
                    .toList();
        } else if (request.sharedExtendedProperty() != null) {
            String[] property = request.sharedExtendedProperty().split("=", 2);
            matching = calendar.events().values().stream()
                    .filter(stored -> !stored.cancelled())
                    .filter(stored -> hasShared(stored.event(), property[0], property[1]))
                    .toList();
        } else {
            matching = calendar.events().values().stream()
                    .filter(stored -> endsAfter(stored.event(), request.timeMin()))
                    .toList();
        }
        int offset = request.pageToken() == null ? 0 : Integer.parseInt(request.pageToken());
        int end = Math.min(offset + pageSize, matching.size());
        String nextPage = end < matching.size() ? String.valueOf(end) : null;
        String nextSync = nextPage == null && request.sharedExtendedProperty() == null ? syncToken() : null;
        List<Event> items = matching.subList(offset, end).stream().map(stored -> copy(stored.event())).toList();
        return new EventPage(items, nextPage, nextSync);
    }

    @Override
    public synchronized Event getEvent(GoogleOAuthToken account, String calendarId, String eventId) {
        return copy(live(readable(account, calendarId), eventId));
    }

    @Override
    public synchronized Event insertEvent(GoogleOAuthToken account, String calendarId, Event event,
                                          boolean notifyGuests) {
        FakeCalendar calendar = writable(account, calendarId);
        Event created = copy(event);
        if (created.getId() == null) {
            created.setId("fake" + (++idCounter));
        }
        Stored existing = calendar.events().get(created.getId());
        if (existing != null && !existing.cancelled()) {
            throw failure(GoogleCalendarException.Kind.REJECTED, "The requested identifier already exists.");
        }
        if (created.getICalUID() == null) {
            created.setICalUID(created.getId() + "@google.com");
        }
        if (created.getStatus() == null) {
            created.setStatus("confirmed");
        }
        created.setHtmlLink("https://calendar.google.com/event?eid=" + created.getId());
        created.setOrganizer(new Event.Organizer().setEmail(calendarId).setSelf(true));
        return copy(store(calendar, created));
    }

    @Override
    public synchronized Event updateEvent(GoogleOAuthToken account, String calendarId, String eventId,
                                          Event event, String etag, boolean notifyGuests) {
        FakeCalendar calendar = writable(account, calendarId);
        Event existing = liveOrIssued(calendar, eventId);
        checkEtag(existing, etag);
        Event updated = copy(event);
        updated.setId(eventId);
        updated.setICalUID(existing.getICalUID());
        updated.setRecurringEventId(existing.getRecurringEventId());
        updated.setOriginalStartTime(existing.getOriginalStartTime());
        updated.setHtmlLink(existing.getHtmlLink());
        updated.setOrganizer(existing.getOrganizer());
        if (updated.getStatus() == null) {
            updated.setStatus("confirmed");
        }
        issuedInstances.remove(calendarId + "/" + eventId);
        return copy(store(calendar, updated));
    }

    @Override
    public synchronized void deleteEvent(GoogleOAuthToken account, String calendarId, String eventId,
                                         String etag) {
        FakeCalendar calendar = writable(account, calendarId);
        Event existing = liveOrIssued(calendar, eventId);
        checkEtag(existing, etag);
        issuedInstances.remove(calendarId + "/" + eventId);
        store(calendar, tombstone(existing));
        if (existing.getRecurrence() != null) {
            // Deleting a series deletes its changed and cancelled occurrences with it.
            for (Stored stored : List.copyOf(calendar.events().values())) {
                if (eventId.equals(stored.event().getRecurringEventId()) && !stored.cancelled()) {
                    store(calendar, tombstone(stored.event()));
                }
            }
        }
    }

    @Override
    public synchronized Event getInstance(GoogleOAuthToken account, String calendarId, String masterEventId,
                                          EventDateTime originalStart) {
        FakeCalendar calendar = readable(account, calendarId);
        Event master = live(calendar, masterEventId);
        if (master.getRecurrence() == null) {
            throw failure(GoogleCalendarException.Kind.REJECTED, "Not a recurring event.");
        }
        String instanceId = masterEventId + "_" + compact(originalStart);
        Stored stored = calendar.events().get(instanceId);
        if (stored != null) {
            if (stored.cancelled()) {
                throw failure(GoogleCalendarException.Kind.GONE, "Not Found");
            }
            return copy(stored.event());
        }
        Event instance = copy(master);
        instance.setRecurrence(null);
        instance.setId(instanceId);
        instance.setRecurringEventId(masterEventId);
        instance.setOriginalStartTime(originalStart.clone());
        instance.setStart(originalStart.clone());
        instance.setEnd(shift(originalStart, master.getStart(), master.getEnd()));
        issuedInstances.put(calendarId + "/" + instanceId, instance);
        return copy(instance);
    }

    @Override
    public synchronized Event moveEvent(GoogleOAuthToken account, String calendarId, String eventId,
                                        String destinationCalendarId) {
        FakeCalendar source = writable(account, calendarId);
        FakeCalendar destination = writable(account, destinationCalendarId);
        Event existing = live(source, eventId);
        if (existing.getRecurringEventId() != null) {
            throw failure(GoogleCalendarException.Kind.REJECTED, "Cannot move one instance of a recurring event.");
        }
        store(source, tombstone(existing));
        Event moved = copy(existing);
        moved.setOrganizer(new Event.Organizer().setEmail(destinationCalendarId).setSelf(true));
        return copy(store(destination, moved));
    }

    @Override
    public synchronized List<CalendarListEntry> listCalendars(GoogleOAuthToken account) {
        checkFailure();
        List<CalendarListEntry> entries = new ArrayList<>();
        access.getOrDefault(account.getId(), Map.of()).forEach((calendarId, role) -> {
            FakeCalendar calendar = calendars.get(calendarId);
            entries.add(new CalendarListEntry()
                    .setId(calendar.id())
                    .setSummary(calendar.summary())
                    .setTimeZone(calendar.timeZone())
                    .setBackgroundColor("#9fc6e7")
                    .setPrimary(calendarId.equals(primaries.get(account.getId())) ? Boolean.TRUE : null)
                    .setAccessRole(googleRole(role)));
        });
        return entries;
    }

    private FakeCalendar readable(GoogleOAuthToken account, String calendarId) {
        checkFailure();
        GoogleAccessRole role = access.getOrDefault(account.getId(), Map.of()).get(calendarId);
        FakeCalendar calendar = calendars.get(calendarId);
        if (role == null || calendar == null) {
            throw failure(GoogleCalendarException.Kind.GONE, "Not Found");
        }
        return calendar;
    }

    private FakeCalendar writable(GoogleOAuthToken account, String calendarId) {
        FakeCalendar calendar = readable(account, calendarId);
        if (!access.get(account.getId()).get(calendarId).canWrite()) {
            throw failure(GoogleCalendarException.Kind.FORBIDDEN,
                    "You need to have writer access to this calendar.");
        }
        return calendar;
    }

    private void checkFailure() {
        if (failAll != null) {
            throw failure(failAll, "Fake Google is down");
        }
        if (failNext != null) {
            GoogleCalendarException.Kind kind = failNext;
            failNext = null;
            throw failure(kind, "Fake Google failure");
        }
    }

    private static GoogleCalendarException failure(GoogleCalendarException.Kind kind, String message) {
        return new GoogleCalendarException(kind, message, null, null);
    }

    private Event live(FakeCalendar calendar, String eventId) {
        Stored stored = calendar.events().get(eventId);
        if (stored == null || stored.cancelled()) {
            throw failure(GoogleCalendarException.Kind.GONE, "Not Found");
        }
        return stored.event();
    }

    private Event liveOrIssued(FakeCalendar calendar, String eventId) {
        Event issued = issuedInstances.get(calendar.id() + "/" + eventId);
        return issued != null && !calendar.events().containsKey(eventId) ? issued : live(calendar, eventId);
    }

    private static void checkEtag(Event existing, String etag) {
        if (etag != null && !etag.equals(existing.getEtag())) {
            throw failure(GoogleCalendarException.Kind.CONFLICT, "Precondition Failed");
        }
    }

    private Event store(FakeCalendar calendar, Event event) {
        version++;
        event.setEtag("\"" + version + "\"");
        event.setUpdated(new DateTime(Instant.now().toEpochMilli()));
        calendar.events().put(event.getId(), new Stored(event, version));
        return event;
    }

    // What Google returns for a deleted event or a cancelled occurrence.
    private static Event tombstone(Event event) {
        Event tombstone = new Event()
                .setId(event.getId())
                .setStatus("cancelled")
                .setRecurringEventId(event.getRecurringEventId());
        if (event.getOriginalStartTime() != null) {
            tombstone.setOriginalStartTime(event.getOriginalStartTime().clone());
        }
        return tombstone;
    }

    private String syncToken() {
        return "g" + generation + "-v" + version;
    }

    private long parseSyncToken(String token) {
        String[] parts = token.split("-v", 2);
        if (parts.length != 2 || !parts[0].equals("g" + generation)) {
            throw failure(GoogleCalendarException.Kind.SYNC_TOKEN_EXPIRED, "Sync token is no longer valid");
        }
        return Long.parseLong(parts[1]);
    }

    private static boolean hasShared(Event event, String key, String value) {
        return event.getExtendedProperties() != null && event.getExtendedProperties().getShared() != null
                && value.equals(event.getExtendedProperties().getShared().get(key));
    }

    // Google keeps series and their occurrences whatever timeMin says; singles must end after it.
    private static boolean endsAfter(Event event, Instant timeMin) {
        if (timeMin == null || event.getRecurrence() != null || event.getRecurringEventId() != null
                || event.getEnd() == null) {
            return true;
        }
        Instant end = event.getEnd().getDateTime() != null
                ? Instant.ofEpochMilli(event.getEnd().getDateTime().getValue())
                : LocalDate.parse(event.getEnd().getDate().toStringRfc3339()).atStartOfDay(ZoneOffset.UTC).toInstant();
        return end.isAfter(timeMin);
    }

    private static String compact(EventDateTime start) {
        if (start.getDateTime() != null) {
            return COMPACT_UTC.format(Instant.ofEpochMilli(start.getDateTime().getValue()));
        }
        return start.getDate().toStringRfc3339().replace("-", "");
    }

    // The occurrence's end: its start plus the master's length.
    private static EventDateTime shift(EventDateTime occurrence, EventDateTime masterStart, EventDateTime masterEnd) {
        if (occurrence.getDateTime() != null) {
            long length = masterEnd.getDateTime().getValue() - masterStart.getDateTime().getValue();
            return new EventDateTime()
                    .setDateTime(new DateTime(occurrence.getDateTime().getValue() + length))
                    .setTimeZone(occurrence.getTimeZone());
        }
        long days = ChronoUnit.DAYS.between(LocalDate.parse(masterStart.getDate().toStringRfc3339()),
                LocalDate.parse(masterEnd.getDate().toStringRfc3339()));
        LocalDate end = LocalDate.parse(occurrence.getDate().toStringRfc3339()).plus(Duration.ofDays(days).toDays(),
                ChronoUnit.DAYS);
        return new EventDateTime().setDate(new DateTime(end.toString()));
    }

    // Through JSON, the way events cross the wire: Event.clone() cannot copy the
    // immutable lists and maps (List.of, Map.of) callers build events with.
    private static Event copy(Event event) {
        try {
            GsonFactory json = GsonFactory.getDefaultInstance();
            return json.fromString(json.toString(event), Event.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String googleRole(GoogleAccessRole role) {
        return switch (role) {
            case OWNER -> "owner";
            case WRITER -> "writer";
            case READER -> "reader";
            case FREE_BUSY_READER -> "freeBusyReader";
        };
    }
}
