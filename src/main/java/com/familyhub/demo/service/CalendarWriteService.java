package com.familyhub.demo.service;

import com.familyhub.demo.dto.calendar.CalendarEventResponse;
import com.familyhub.demo.dto.calendar.CalendarWriteRequest;
import com.familyhub.demo.event.CalendarSyncRequested;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.exception.CalendarWriteException;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.google.GoogleCalendarException;
import com.familyhub.demo.google.GoogleCalendarGateway;
import com.familyhub.demo.google.GoogleEventCacheMapper;
import com.familyhub.demo.google.GoogleEventWriter;
import com.familyhub.demo.google.MemberAttribution;
import com.familyhub.demo.google.SeriesSplitter;
import com.familyhub.demo.model.Family;
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
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.TimeZone;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Writes events to Google (Phase 4): Google first, then the cache from Google's answer,
 * so the app shows the write at once and the cache never holds what Google refused.
 * Google is called outside any transaction. Each write asks for a sync of its calendar,
 * and a conflict asks for one too, so the next read has Google's current copy.
 */
@Slf4j
@Service
public class CalendarWriteService {

    private static final DateTimeFormatter COMPACT_UTC =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final TimeZone UTC = TimeZone.getTimeZone("UTC");

    private final GoogleCalendarGateway gateway;
    private final GoogleCalendarRepository calendars;
    private final GoogleCalendarAccessRepository access;
    private final GoogleEventRepository events;
    private final FamilyMemberRepository members;
    private final FamilyRepository families;
    private final GoogleOAuthTokenRepository tokens;
    private final GoogleEventCacheMapper mapper;
    private final MemberAttribution attribution;
    private final GoogleEventWriter writer;
    private final SeriesSplitter splitter;
    private final ApplicationEventPublisher publisher;
    private final TransactionTemplate transactions;

    /**
     * Creates the service.
     *
     * @param gateway            Google
     * @param calendars          cached calendars
     * @param access             accounts' roles on them
     * @param events             cached events
     * @param members            family members
     * @param families           families, for the default calendar
     * @param tokens             signed-in accounts
     * @param mapper             Google event to cache row
     * @param attribution        whose lanes an event goes in
     * @param writer             request to Google event
     * @param splitter           splits a series for "this and following"
     * @param publisher          asks for syncs
     * @param transactionManager runs each cache write in one transaction
     */
    public CalendarWriteService(GoogleCalendarGateway gateway, GoogleCalendarRepository calendars,
                                GoogleCalendarAccessRepository access, GoogleEventRepository events,
                                FamilyMemberRepository members, FamilyRepository families,
                                GoogleOAuthTokenRepository tokens, GoogleEventCacheMapper mapper,
                                MemberAttribution attribution, GoogleEventWriter writer, SeriesSplitter splitter,
                                ApplicationEventPublisher publisher, PlatformTransactionManager transactionManager) {
        this.gateway = gateway;
        this.calendars = calendars;
        this.access = access;
        this.events = events;
        this.members = members;
        this.families = families;
        this.tokens = tokens;
        this.mapper = mapper;
        this.attribution = attribution;
        this.writer = writer;
        this.splitter = splitter;
        this.publisher = publisher;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Creates an event on the calendar the request names, else the family's default.
     *
     * @param family  the signed-in family
     * @param request the event
     * @return the created event as the calendar shows it
     * @throws CalendarWriteException    if Google refuses, or there is no calendar to write to
     * @throws ResourceNotFoundException if a member or the calendar is not the family's
     * @throws BadRequestException       if the times are incomplete or backwards
     */
    public CalendarEventResponse create(Family family, CalendarWriteRequest request) {
        validate(family, request);
        GoogleCalendar calendar = target(family, request.calendarId());
        GoogleOAuthToken account = writingAccount(calendar);
        Event event = writer.newEvent(request, zoneOf(calendar, family).getId());
        Event created = google(account, calendar,
                () -> gateway.insertEvent(account, calendar.getGoogleCalendarId(), event, request.notifyGuests()));
        CalendarEventResponse response = transactions.execute(status -> cacheAndRespond(calendar.getId(), created));
        requestSync(calendar.getId());
        log.info("Calendar event created, calendarId={}, googleEventId={}", calendar.getId(), created.getId());
        return response;
    }

    /**
     * Changes an event (a single one, a whole series, or one already changed occurrence)
     * on top of Google's copy, and moves it when the request names another calendar.
     *
     * @param family  the signed-in family
     * @param eventId the cache row's id
     * @param request the event's new state; its etag is the one it was read with
     * @return the changed event as the calendar shows it
     * @throws CalendarWriteException    if Google refuses, or the event changed in Google meanwhile
     * @throws ResourceNotFoundException if the event, a member or the calendar is not the family's
     */
    public CalendarEventResponse update(Family family, UUID eventId, CalendarWriteRequest request) {
        validate(family, request);
        GoogleEvent row = familysRow(family, eventId);
        GoogleCalendar source = row.getCalendar();
        GoogleCalendar destination = source;
        GoogleOAuthToken account = writingAccount(source);
        Event current = writer.parse(row.getRaw());
        String etag = request.etag() != null ? request.etag() : row.getEtag();
        String googleId = row.getGoogleEventId();

        if (request.calendarId() != null && !request.calendarId().equals(source.getId())) {
            if (row.getRecurringEventId() != null) {
                throw new BadRequestException("One date of a series cannot move to another calendar. "
                        + "Move the whole series instead.");
            }
            destination = target(family, request.calendarId());
            GoogleCalendar to = destination;
            writingAccount(to);
            GoogleOAuthToken mover = account;
            current = google(account, source,
                    () -> gateway.moveEvent(mover, source.getGoogleCalendarId(), googleId, to.getGoogleCalendarId()));
            etag = current.getEtag();
            account = writingAccount(to);
            transactions.executeWithoutResult(status -> deleteWithOccurrences(source.getId(), googleId));
            requestSync(source.getId());
        }

        GoogleCalendar onto = destination;
        GoogleOAuthToken writerAccount = account;
        Event edited = writer.applyTo(current, request, zoneOf(onto, family).getId());
        String sentEtag = etag;
        Event updated = google(writerAccount, onto, () -> gateway.updateEvent(writerAccount,
                onto.getGoogleCalendarId(), googleId, edited, sentEtag, request.notifyGuests()));
        CalendarEventResponse response = transactions.execute(status -> cacheAndRespond(onto.getId(), updated));
        requestSync(onto.getId());
        log.info("Calendar event updated, calendarId={}, googleEventId={}", onto.getId(), googleId);
        return response;
    }

    /**
     * Deletes an event; for a series, with all its occurrences. An event already gone
     * from Google counts as deleted.
     *
     * @param family  the signed-in family
     * @param eventId the cache row's id
     * @param etag    the etag it was read with, or null to delete whatever Google holds
     * @throws CalendarWriteException    if Google refuses, or the event changed in Google meanwhile
     * @throws ResourceNotFoundException if the event is not the family's
     */
    public void delete(Family family, UUID eventId, String etag) {
        GoogleEvent row = familysRow(family, eventId);
        GoogleCalendar calendar = row.getCalendar();
        GoogleOAuthToken account = writingAccount(calendar);
        try {
            google(account, calendar, () -> {
                gateway.deleteEvent(account, calendar.getGoogleCalendarId(), row.getGoogleEventId(),
                        etag != null ? etag : row.getEtag());
                return null;
            });
        } catch (CalendarWriteException e) {
            if (e.getCode() != CalendarWriteException.Code.EVENT_GONE) {
                throw e;
            }
        }
        transactions.executeWithoutResult(status -> deleteWithOccurrences(calendar.getId(), row.getGoogleEventId()));
        requestSync(calendar.getId());
        log.info("Calendar event deleted, calendarId={}, googleEventId={}", calendar.getId(), row.getGoogleEventId());
    }

    /**
     * Changes one date of a series on its own ("this"): Google makes it an instance of the
     * series; the rest of the series stays as it was.
     *
     * @param family        the signed-in family
     * @param seriesId      the series' master row
     * @param originalStart the date's original start, compact ({@code 20261114T000000Z} or {@code 20261114})
     * @param request       the date's new state; recurrence lines are ignored
     * @return the changed date as the calendar shows it
     * @throws BadRequestException       if it is not a series, or the date is not in Google's form
     * @throws CalendarWriteException    if Google refuses, or the date is not one of the series
     * @throws ResourceNotFoundException if the series or a member is not the family's
     */
    public CalendarEventResponse updateOccurrence(Family family, UUID seriesId, String originalStart,
                                                  CalendarWriteRequest request) {
        validate(family, request);
        GoogleEvent master = familysSeries(family, seriesId);
        GoogleCalendar calendar = master.getCalendar();
        GoogleOAuthToken account = writingAccount(calendar);
        Event instance = occurrence(account, calendar, master, originalStart);
        Event edited = writer.applyTo(instance, request.withRecurrence(null), zoneOf(calendar, family).getId());
        Event updated = google(account, calendar, () -> gateway.updateEvent(account, calendar.getGoogleCalendarId(),
                instance.getId(), edited, instance.getEtag(), request.notifyGuests()));
        CalendarEventResponse response = transactions.execute(status -> {
            GoogleEvent row = cacheRow(calendar.getId(), updated);
            return CalendarEventResponses.forInstance(row, master, originalStart, canWrite(calendar.getId()),
                    zoneOf(calendar, family));
        });
        requestSync(calendar.getId());
        log.info("Calendar occurrence updated, calendarId={}, googleEventId={}", calendar.getId(), instance.getId());
        return response;
    }

    /**
     * Cancels one date of a series on its own ("this").
     *
     * @param family        the signed-in family
     * @param seriesId      the series' master row
     * @param originalStart the date's original start, compact
     * @throws BadRequestException       if it is not a series, or the date is not in Google's form
     * @throws CalendarWriteException    if Google refuses, or the date is not one of the series
     * @throws ResourceNotFoundException if the series is not the family's
     */
    public void deleteOccurrence(Family family, UUID seriesId, String originalStart) {
        GoogleEvent master = familysSeries(family, seriesId);
        GoogleCalendar calendar = master.getCalendar();
        GoogleOAuthToken account = writingAccount(calendar);
        Event instance = occurrence(account, calendar, master, originalStart);
        google(account, calendar, () -> {
            gateway.deleteEvent(account, calendar.getGoogleCalendarId(), instance.getId(), instance.getEtag());
            return null;
        });
        // What Google keeps for a cancelled date: it hides that date of the series.
        Event cancelled = new Event()
                .setId(instance.getId())
                .setStatus("cancelled")
                .setRecurringEventId(master.getGoogleEventId())
                .setOriginalStartTime(instance.getOriginalStartTime() != null
                        ? instance.getOriginalStartTime() : originalStartTime(originalStart, master));
        transactions.executeWithoutResult(status -> cacheRow(calendar.getId(), cancelled));
        requestSync(calendar.getId());
        log.info("Calendar occurrence cancelled, calendarId={}, googleEventId={}", calendar.getId(), instance.getId());
    }

    /**
     * Changes a date of a series and every date after it ("this and following"), as
     * Google's app does: the series ends before the date and a new series starts there
     * with the change. Dates changed on their own from there on follow the new series. From
     * the first date it is the whole series.
     *
     * @param family        the signed-in family
     * @param seriesId      the series' master row
     * @param originalStart the first changed date's original start, compact
     * @param request       the new series' state; null recurrence keeps the rule
     * @return the new series as the calendar shows it
     * @throws BadRequestException       if it is not a series, or the date is not in Google's form
     * @throws CalendarWriteException    if Google refuses
     * @throws ResourceNotFoundException if the series or a member is not the family's
     */
    public CalendarEventResponse updateFollowing(Family family, UUID seriesId, String originalStart,
                                                 CalendarWriteRequest request) {
        validate(family, request);
        GoogleEvent master = familysSeries(family, seriesId);
        SeriesSplitter.Split split = split(master, originalStart, family);
        if (split.startsAtFirst()) {
            return update(family, seriesId, request);
        }
        GoogleCalendar calendar = master.getCalendar();
        GoogleOAuthToken account = writingAccount(calendar);
        // Each step works on its own copy of Google's event.
        Event ended = endSeries(account, calendar, master, writer.parse(master.getRaw()), split);
        CalendarWriteRequest following = request.recurrence() != null ? request
                : request.withRecurrence(List.of(split.after()));
        Event fresh = writer.applyTo(withoutIdentity(writer.parse(master.getRaw())), following,
                zoneOf(calendar, family).getId());
        Event created = google(account, calendar, () -> gateway.insertEvent(account, calendar.getGoogleCalendarId(),
                fresh, request.notifyGuests()));
        dropDatesFrom(account, calendar, master, originalStart);
        CalendarEventResponse response = transactions.execute(status -> {
            cacheRow(calendar.getId(), ended);
            return respond(calendar.getId(), cacheRow(calendar.getId(), created));
        });
        requestSync(calendar.getId());
        log.info("Calendar series split, calendarId={}, from={}, newGoogleEventId={}", calendar.getId(),
                originalStart, created.getId());
        return response;
    }

    /**
     * Deletes a date of a series and every date after it: the series ends before it. From
     * the first date it deletes the whole series.
     *
     * @param family        the signed-in family
     * @param seriesId      the series' master row
     * @param originalStart the first deleted date's original start, compact
     * @throws BadRequestException       if it is not a series, or the date is not in Google's form
     * @throws CalendarWriteException    if Google refuses
     * @throws ResourceNotFoundException if the series is not the family's
     */
    public void deleteFollowing(Family family, UUID seriesId, String originalStart) {
        GoogleEvent master = familysSeries(family, seriesId);
        SeriesSplitter.Split split = split(master, originalStart, family);
        if (split.startsAtFirst()) {
            delete(family, seriesId, null);
            return;
        }
        GoogleCalendar calendar = master.getCalendar();
        GoogleOAuthToken account = writingAccount(calendar);
        Event ended = endSeries(account, calendar, master, writer.parse(master.getRaw()), split);
        dropDatesFrom(account, calendar, master, originalStart);
        transactions.executeWithoutResult(status -> cacheRow(calendar.getId(), ended));
        requestSync(calendar.getId());
        log.info("Calendar series ended, calendarId={}, from={}", calendar.getId(), originalStart);
    }

    private GoogleEvent familysSeries(Family family, UUID seriesId) {
        GoogleEvent master = familysRow(family, seriesId);
        if (master.getRecurrence() == null || master.getRecurringEventId() != null) {
            throw new BadRequestException("This event is not a recurring series.");
        }
        return master;
    }

    private Event occurrence(GoogleOAuthToken account, GoogleCalendar calendar, GoogleEvent master,
                             String originalStart) {
        EventDateTime original = originalStartTime(originalStart, master);
        return google(account, calendar, () -> gateway.getInstance(account, calendar.getGoogleCalendarId(),
                master.getGoogleEventId(), original));
    }

    // Google's compact form: 20261114T000000Z for a timed series, 20261114 for an all-day one.
    private static EventDateTime originalStartTime(String originalStart, GoogleEvent master) {
        try {
            if (master.isAllDay() && originalStart.length() == 8) {
                LocalDate date = LocalDate.parse(originalStart, DateTimeFormatter.BASIC_ISO_DATE);
                return new EventDateTime().setDate(new DateTime(date.toString()));
            }
            if (!master.isAllDay() && originalStart.length() == 16) {
                Instant start = Instant.from(COMPACT_UTC.parse(originalStart));
                return new EventDateTime().setDateTime(new DateTime(Date.from(start), UTC))
                        .setTimeZone(master.getTimeZone());
            }
        } catch (DateTimeException e) {
            // Falls through to the refusal below.
        }
        throw new BadRequestException("\"" + originalStart + "\" is not a date of this series.");
    }

    private SeriesSplitter.Split split(GoogleEvent master, String originalStart, Family family) {
        EventDateTime original = originalStartTime(originalStart, master);
        if (master.isAllDay()) {
            return splitter.splitAllDay(master.getRecurrence(), master.getStartDate(),
                    LocalDate.parse(original.getDate().toStringRfc3339()));
        }
        return splitter.splitTimed(master.getRecurrence(), master.getStartAt(),
                Instant.ofEpochMilli(original.getDateTime().getValue()),
                zoneOf(master.getCalendar(), family));
    }

    private Event endSeries(GoogleOAuthToken account, GoogleCalendar calendar, GoogleEvent master, Event current,
                            SeriesSplitter.Split split) {
        current.setRecurrence(new java.util.ArrayList<>(List.of(split.before())));
        return google(account, calendar, () -> gateway.updateEvent(account, calendar.getGoogleCalendarId(),
                master.getGoogleEventId(), current, master.getEtag(), false));
    }

    // The instances of the old series from the split on: deleted in Google, as its app does.
    private void dropDatesFrom(GoogleOAuthToken account, GoogleCalendar calendar, GoogleEvent master,
                               String originalStart) {
        EventDateTime split = originalStartTime(originalStart, master);
        List<GoogleEvent> later = transactions.execute(status -> events.findAllByCalendarId(calendar.getId()).stream()
                .filter(row -> master.getGoogleEventId().equals(row.getRecurringEventId()))
                .filter(row -> master.isAllDay()
                        ? row.getOriginalStartDate() != null && !row.getOriginalStartDate()
                        .isBefore(LocalDate.parse(split.getDate().toStringRfc3339()))
                        : row.getOriginalStartAt() != null && row.getOriginalStartAt().toEpochMilli()
                        >= split.getDateTime().getValue())
                .toList());
        for (GoogleEvent row : later) {
            if (row.getStatus() == GoogleEventStatus.CANCELLED) {
                continue;
            }
            try {
                google(account, calendar, () -> {
                    gateway.deleteEvent(account, calendar.getGoogleCalendarId(), row.getGoogleEventId(), null);
                    return null;
                });
            } catch (CalendarWriteException e) {
                if (e.getCode() != CalendarWriteException.Code.EVENT_GONE) {
                    throw e;
                }
            }
        }
        transactions.executeWithoutResult(status ->
                later.forEach(row -> events.findById(row.getId()).ifPresent(events::delete)));
    }

    // Strips what makes Google take the event as an existing one; works on the given copy.
    private static Event withoutIdentity(Event copy) {
        copy.setId(null);
        copy.setICalUID(null);
        copy.setEtag(null);
        copy.setHtmlLink(null);
        copy.setRecurringEventId(null);
        copy.setOriginalStartTime(null);
        copy.setSequence(null);
        copy.setCreated(null);
        copy.setUpdated(null);
        copy.setOrganizer(null);
        copy.setCreator(null);
        return copy;
    }

    private void validate(Family family, CalendarWriteRequest request) {
        if (request.allDay()) {
            if (request.startDate() == null || request.endDate() == null) {
                throw new BadRequestException("An all-day event needs its first day and the day after its last");
            }
            if (!request.endDate().isAfter(request.startDate())) {
                throw new BadRequestException("An all-day event must end after the day it starts");
            }
        } else {
            if (request.start() == null || request.end() == null) {
                throw new BadRequestException("A timed event needs a start and an end");
            }
            if (request.end().isBefore(request.start())) {
                throw new BadRequestException("Start time must be before end time");
            }
        }
        for (UUID memberId : request.memberIds()) {
            members.findById(memberId)
                    .filter(member -> member.getFamily().getId().equals(family.getId()))
                    .orElseThrow(() -> new ResourceNotFoundException("Family Member", memberId));
        }
    }

    private GoogleCalendar target(Family family, UUID calendarId) {
        UUID id = calendarId != null ? calendarId
                : families.findById(family.getId()).map(Family::getDefaultGoogleCalendarId).orElse(null);
        if (id == null) {
            throw new CalendarWriteException(CalendarWriteException.Code.GOOGLE_NOT_CONNECTED,
                    "Choose a Google calendar for new events in Family Settings first.", null);
        }
        GoogleCalendar calendar = calendars.findForSync(id)
                .filter(found -> found.getFamily().getId().equals(family.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Google calendar", id));
        if (!calendar.isEnabled()) {
            throw new BadRequestException("\"" + calendar.getSummary() + "\" is switched off in Family Settings.");
        }
        return calendar;
    }

    private GoogleEvent familysRow(Family family, UUID eventId) {
        return transactions.execute(status -> {
            GoogleEvent row = events.findById(eventId)
                    .filter(found -> found.getCalendar().getFamily().getId().equals(family.getId()))
                    .orElseThrow(() -> new ResourceNotFoundException("Calendar Event", eventId));
            // Loaded here so the row can be used after the transaction.
            row.getCalendar().getSummary();
            return row;
        });
    }

    // An owner's sign-in, else a writer's; never one Google refused or without the write scope.
    private GoogleOAuthToken writingAccount(GoogleCalendar calendar) {
        List<GoogleCalendarAccess> rows = access.findByCalendarId(calendar.getId());
        return rows.stream()
                .filter(GoogleCalendarAdminService::canWrite)
                .min(Comparator.comparing(GoogleCalendarAccess::getAccessRole))
                .map(GoogleCalendarAccess::getToken)
                .orElseThrow(() -> rows.stream().anyMatch(row -> row.getAccessRole().canWrite())
                        ? new CalendarWriteException(CalendarWriteException.Code.GOOGLE_REAUTH_REQUIRED,
                        "Connect Google again in Family Settings so the hub may change \"" + calendar.getSummary()
                                + "\".", null)
                        : new CalendarWriteException(CalendarWriteException.Code.GOOGLE_NO_WRITE_ACCESS,
                        "No one signed in to the hub may change \"" + calendar.getSummary() + "\".", null));
    }

    private <T> T google(GoogleOAuthToken account, GoogleCalendar calendar, Supplier<T> call) {
        try {
            return call.get();
        } catch (GoogleCalendarException e) {
            throw translate(e, account, calendar);
        }
    }

    private CalendarWriteException translate(GoogleCalendarException failure, GoogleOAuthToken account,
                                             GoogleCalendar calendar) {
        log.warn("Google refused a calendar write, calendarId={}, kind={}: {}", calendar.getId(), failure.getKind(),
                failure.getMessage());
        return switch (failure.getKind()) {
            case CONFLICT -> {
                requestSync(calendar.getId());
                yield new CalendarWriteException(CalendarWriteException.Code.EVENT_CONFLICT,
                        "This event changed in Google since it was opened. Open it again and redo the change.", null);
            }
            case GONE -> {
                requestSync(calendar.getId());
                yield new CalendarWriteException(CalendarWriteException.Code.EVENT_GONE,
                        "This event is no longer in Google Calendar.", null);
            }
            case FORBIDDEN -> new CalendarWriteException(CalendarWriteException.Code.GOOGLE_NO_WRITE_ACCESS,
                    "Google does not let the hub change \"" + calendar.getSummary() + "\".", null);
            case REJECTED -> new CalendarWriteException(CalendarWriteException.Code.GOOGLE_REJECTED,
                    failure.getMessage(), null);
            case REAUTH_REQUIRED -> {
                tokens.markNeedsReauth(account.getId());
                yield new CalendarWriteException(CalendarWriteException.Code.GOOGLE_REAUTH_REQUIRED,
                        "Google asked for the account to be connected again in Family Settings.", null);
            }
            case RATE_LIMITED -> new CalendarWriteException(CalendarWriteException.Code.GOOGLE_RATE_LIMITED,
                    "Google asked the hub to slow down. Try again in a minute.", failure.getRetryAfter().orElse(null));
            case UNAVAILABLE, SYNC_TOKEN_EXPIRED -> new CalendarWriteException(
                    CalendarWriteException.Code.GOOGLE_UNAVAILABLE,
                    "Google Calendar can't be reached right now. Try again in a minute.", null);
        };
    }

    private CalendarEventResponse cacheAndRespond(UUID calendarId, Event saved) {
        return respond(calendarId, cacheRow(calendarId, saved));
    }

    // In a transaction: Google's answer onto its cache row (kept by Google id, so its id stays).
    private GoogleEvent cacheRow(UUID calendarId, Event saved) {
        GoogleCalendar calendar = calendars.findForSync(calendarId).orElseThrow();
        List<FamilyMember> familyMembers = members.findByFamily(calendar.getFamily());
        GoogleEvent row = events.findByCalendarAndGoogleEventId(calendar, saved.getId()).orElseGet(GoogleEvent::new);
        UUID[] memberIds = attribution.membersOf(saved, calendar, familyMembers).toArray(UUID[]::new);
        return events.save(mapper.toCache(saved, calendar, memberIds, row));
    }

    private CalendarEventResponse respond(UUID calendarId, GoogleEvent row) {
        GoogleCalendar calendar = row.getCalendar();
        return CalendarEventResponses.forRow(row, canWrite(calendarId), zoneOf(calendar, calendar.getFamily()));
    }

    private boolean canWrite(UUID calendarId) {
        return access.findByCalendarId(calendarId).stream().anyMatch(GoogleCalendarAdminService::canWrite);
    }

    private void deleteWithOccurrences(UUID calendarId, String googleEventId) {
        for (GoogleEvent row : events.findAllByCalendarId(calendarId)) {
            if (googleEventId.equals(row.getGoogleEventId()) || googleEventId.equals(row.getRecurringEventId())) {
                events.delete(row);
            }
        }
    }

    private void requestSync(UUID calendarId) {
        publisher.publishEvent(new CalendarSyncRequested(calendarId));
    }

    private static ZoneId zoneOf(GoogleCalendar calendar, Family family) {
        ZoneId familyZone = ZoneId.of(FamilyTimezoneResolver.resolveStoredTimezoneOrDefault(family.getTimezone()));
        return CalendarEventResponses.zoneOf(calendar, familyZone);
    }

}
