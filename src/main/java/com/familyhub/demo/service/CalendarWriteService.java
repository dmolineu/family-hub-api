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
import com.familyhub.demo.model.Family;
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
import com.google.api.services.calendar.model.Event;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
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
     * @param publisher          asks for syncs
     * @param transactionManager runs each cache write in one transaction
     */
    public CalendarWriteService(GoogleCalendarGateway gateway, GoogleCalendarRepository calendars,
                                GoogleCalendarAccessRepository access, GoogleEventRepository events,
                                FamilyMemberRepository members, FamilyRepository families,
                                GoogleOAuthTokenRepository tokens, GoogleEventCacheMapper mapper,
                                MemberAttribution attribution, GoogleEventWriter writer,
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
        GoogleCalendar calendar = calendars.findForSync(calendarId).orElseThrow();
        List<FamilyMember> familyMembers = members.findByFamily(calendar.getFamily());
        GoogleEvent row = events.findByCalendarAndGoogleEventId(calendar, saved.getId()).orElseGet(GoogleEvent::new);
        UUID[] memberIds = attribution.membersOf(saved, calendar, familyMembers).toArray(UUID[]::new);
        GoogleEvent cached = events.save(mapper.toCache(saved, calendar, memberIds, row));
        boolean canEdit = access.findByCalendarId(calendarId).stream().anyMatch(GoogleCalendarAdminService::canWrite);
        return CalendarEventResponses.forRow(cached, canEdit, zoneOf(calendar, calendar.getFamily()));
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
