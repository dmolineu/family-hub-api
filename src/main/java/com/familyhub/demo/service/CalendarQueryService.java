package com.familyhub.demo.service;

import com.familyhub.demo.dto.calendar.CalendarEventResponse;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.google.GoogleEventCacheMapper;
import com.familyhub.demo.google.GoogleRecurrenceExpander;
import com.familyhub.demo.google.GoogleRecurrenceExpander.Occurrence;
import com.familyhub.demo.google.GoogleRecurrenceExpander.Window;
import com.familyhub.demo.model.CalendarEvent;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.GoogleCalendar;
import com.familyhub.demo.model.GoogleCalendarAccess;
import com.familyhub.demo.model.GoogleEvent;
import com.familyhub.demo.repository.CalendarEventRepository;
import com.familyhub.demo.repository.FamilyMemberRepository;
import com.familyhub.demo.repository.GoogleCalendarAccessRepository;
import com.familyhub.demo.repository.GoogleCalendarRepository;
import com.familyhub.demo.repository.GoogleEventRepository;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Reads the calendar from the Google event cache (Phase 4), so a Google outage never
 * blanks the wall. Singles in the window, series expanded in the family's zone, only
 * enabled calendars; an event two calendars hold (an invitation and the organiser's
 * copy) shows once, preferring the organiser's, then an editable copy.
 */
@Service
@Transactional(readOnly = true)
public class CalendarQueryService {

    /** The longest window one request may ask for. */
    static final long MAX_DAYS = 366;

    private final GoogleCalendarRepository calendars;
    private final GoogleCalendarAccessRepository access;
    private final GoogleEventRepository events;
    private final FamilyMemberRepository members;
    private final GoogleRecurrenceExpander expander;
    private final CalendarEventRepository legacy;
    private final LegacyEventConverter converter;
    private final GoogleEventCacheMapper mapper;

    /**
     * Creates the service.
     *
     * @param calendars cached calendars
     * @param access    accounts' roles on them
     * @param events    cached events
     * @param members   family members, to check a member filter
     * @param expander  expands series
     * @param legacy    the family's own events waiting to be copied to Google
     * @param converter shows them as their Google copies will look
     * @param mapper    Google event to (display) row
     */
    public CalendarQueryService(GoogleCalendarRepository calendars, GoogleCalendarAccessRepository access,
                                GoogleEventRepository events, FamilyMemberRepository members,
                                GoogleRecurrenceExpander expander, CalendarEventRepository legacy,
                                LegacyEventConverter converter, GoogleEventCacheMapper mapper) {
        this.calendars = calendars;
        this.access = access;
        this.events = events;
        this.members = members;
        this.expander = expander;
        this.legacy = legacy;
        this.converter = converter;
        this.mapper = mapper;
    }

    /**
     * The events and occurrences between two days, in the family's zone.
     *
     * @param family   the signed-in family
     * @param from     the first day
     * @param to       the last day, inclusive
     * @param memberId only this member's events, or null for everyone's
     * @return the events, by start
     * @throws BadRequestException       if the window is backwards or longer than a year
     * @throws AccessDeniedException     if the member belongs to another family
     * @throws ResourceNotFoundException if the member does not exist
     */
    public List<CalendarEventResponse> eventsFor(Family family, LocalDate from, LocalDate to, UUID memberId) {
        if (to.isBefore(from)) {
            throw new BadRequestException("The end date must not be before the start date");
        }
        if (ChronoUnit.DAYS.between(from, to) > MAX_DAYS) {
            throw new BadRequestException("Date range must not exceed one year");
        }
        if (memberId != null) {
            FamilyMember member = members.findById(memberId)
                    .orElseThrow(() -> new ResourceNotFoundException("Family Member", memberId));
            if (!member.getFamily().getId().equals(family.getId())) {
                throw new AccessDeniedException("Access Denied -- CalendarQueryService.eventsFor()");
            }
        }
        List<GoogleCalendar> shown = calendars.findEnabledByFamily(family.getId());
        ZoneId familyZone = ZoneId.of(FamilyTimezoneResolver.resolveStoredTimezoneOrDefault(family.getTimezone()));
        Window window = Window.of(from, to, familyZone);
        List<Found> found = new ArrayList<>(waitingToBeCopied(family, shown, window, familyZone));
        if (shown.isEmpty()) {
            return filterAndSort(dedupe(found), memberId, familyZone);
        }
        Set<UUID> editable = editableCalendars(shown);

        for (GoogleEvent single : events.findSinglesInWindow(shown, window.from(), window.to(), window.fromDate(),
                window.toDate())) {
            found.add(new Found(CalendarEventResponses.forRow(single,
                    editable.contains(single.getCalendar().getId()), familyZone), single));
        }
        List<GoogleEvent> masters = events.findMasters(shown);
        Map<String, List<GoogleEvent>> instancesBySeries = instancesBySeries(shown, masters);
        for (GoogleEvent master : masters) {
            ZoneId zone = CalendarEventResponses.zoneOf(master.getCalendar(), familyZone);
            List<GoogleEvent> instances = instancesBySeries.getOrDefault(seriesKey(master), List.of());
            for (Occurrence occurrence : expander.expand(master, instances, zone, window)) {
                found.add(new Found(CalendarEventResponses.forOccurrence(master, occurrence,
                        editable.contains(master.getCalendar().getId()), familyZone), master));
            }
        }

        return filterAndSort(dedupe(found), memberId, familyZone);
    }

    private static List<CalendarEventResponse> filterAndSort(List<CalendarEventResponse> found, UUID memberId,
                                                             ZoneId familyZone) {
        return found.stream()
                .filter(event -> memberId == null || event.memberIds().contains(memberId))
                .sorted(Comparator.comparing((CalendarEventResponse event) -> startInstant(event, familyZone))
                        .thenComparing(CalendarEventResponse::title))
                .toList();
    }

    // The family's own pre-Phase 4 events that wait to be copied to Google, shown read-only,
    // as they will look once copied (on the default calendar, in their member's lane).
    private List<Found> waitingToBeCopied(Family family, List<GoogleCalendar> shown, Window window,
                                          ZoneId familyZone) {
        List<CalendarEvent> waiting = legacy.findUncopied(family.getId());
        if (waiting.isEmpty()) {
            return List.of();
        }
        GoogleCalendar onto = shown.stream()
                .filter(calendar -> calendar.getId().equals(family.getDefaultGoogleCalendarId()))
                .findFirst().orElseGet(GoogleCalendar::new);
        Map<UUID, List<CalendarEvent>> exceptions = new HashMap<>();
        List<CalendarEvent> heads = new ArrayList<>();
        for (CalendarEvent row : waiting) {
            if (row.getRecurringEvent() == null) {
                heads.add(row);
            } else {
                exceptions.computeIfAbsent(row.getRecurringEvent().getId(), id -> new ArrayList<>()).add(row);
            }
        }
        List<Found> found = new ArrayList<>();
        for (CalendarEvent head : heads) {
            List<CalendarEvent> dates = exceptions.getOrDefault(head.getId(), List.of());
            GoogleEvent row = display(converter.toGoogle(head, dates, familyZone), onto, head);
            if (row.getRecurrence() == null) {
                boolean inWindow = row.isAllDay()
                        ? window.overlaps(row.getStartDate(), row.getEndDate())
                        : window.overlaps(row.getStartAt(), row.getEndAt());
                if (inWindow) {
                    found.add(new Found(CalendarEventResponses.forRow(row, false, familyZone), row));
                }
                continue;
            }
            List<GoogleEvent> changed = new ArrayList<>();
            for (CalendarEvent date : dates) {
                if (date.isCancelled()) {
                    continue;
                }
                GoogleEvent instance = display(converter.applyException(new com.google.api.services.calendar.model.Event(),
                        date, familyZone), onto, date);
                instance.setRecurringEventId(row.getGoogleEventId());
                com.google.api.services.calendar.model.EventDateTime original =
                        converter.originalStart(head, date, familyZone);
                if (original.getDateTime() != null) {
                    instance.setOriginalStartAt(Instant.ofEpochMilli(original.getDateTime().getValue()));
                } else {
                    instance.setOriginalStartDate(LocalDate.parse(original.getDate().toStringRfc3339()));
                }
                changed.add(instance);
            }
            for (Occurrence occurrence : expander.expand(row, changed, familyZone, window)) {
                found.add(new Found(CalendarEventResponses.forOccurrence(row, occurrence, false, familyZone), row));
            }
        }
        return found;
    }

    private GoogleEvent display(com.google.api.services.calendar.model.Event event, GoogleCalendar onto,
                                CalendarEvent source) {
        GoogleEvent row = mapper.toCache(event, onto, new UUID[]{source.getMember().getId()}, new GoogleEvent());
        row.setId(source.getId());
        row.setGoogleEventId("hub-" + source.getId());
        return row;
    }

    private Set<UUID> editableCalendars(List<GoogleCalendar> shown) {
        Set<UUID> editable = new HashSet<>();
        for (GoogleCalendarAccess row : access.findByCalendars(shown)) {
            if (GoogleCalendarAdminService.canWrite(row)) {
                editable.add(row.getCalendar().getId());
            }
        }
        return editable;
    }

    private Map<String, List<GoogleEvent>> instancesBySeries(List<GoogleCalendar> shown, List<GoogleEvent> masters) {
        Map<String, List<GoogleEvent>> bySeries = new HashMap<>();
        if (masters.isEmpty()) {
            return bySeries;
        }
        Set<String> ids = new HashSet<>();
        masters.forEach(master -> ids.add(master.getGoogleEventId()));
        for (GoogleEvent instance : events.findInstancesOf(shown, ids)) {
            bySeries.computeIfAbsent(instance.getCalendar().getId() + "/" + instance.getRecurringEventId(),
                    key -> new ArrayList<>()).add(instance);
        }
        return bySeries;
    }

    private static String seriesKey(GoogleEvent master) {
        return master.getCalendar().getId() + "/" + master.getGoogleEventId();
    }

    /** A response and the row it came from: the single event, or the series' master. */
    private record Found(CalendarEventResponse response, GoogleEvent row) {
    }

    // One copy of an event several calendars hold: the organiser's, else an editable one,
    // else the first. Keyed by iCalUID and which occurrence it is.
    private static List<CalendarEventResponse> dedupe(List<Found> found) {
        Map<String, Found> kept = new LinkedHashMap<>();
        List<CalendarEventResponse> result = new ArrayList<>();
        for (Found candidate : found) {
            String uid = candidate.row().getIcalUid();
            if (uid == null) {
                result.add(candidate.response());
                continue;
            }
            CalendarEventResponse event = candidate.response();
            String key = uid + "|" + (event.originalStart() != null ? event.originalStart() : event.start());
            Found current = kept.get(key);
            if (current == null || prefer(candidate, current)) {
                kept.put(key, candidate);
            }
        }
        kept.values().forEach(copy -> result.add(copy.response()));
        return result;
    }

    private static boolean prefer(Found candidate, Found current) {
        if (candidate.row().isOrganizerSelf() != current.row().isOrganizerSelf()) {
            return candidate.row().isOrganizerSelf();
        }
        return candidate.response().canEdit() && !current.response().canEdit();
    }

    private static Instant startInstant(CalendarEventResponse event, ZoneId familyZone) {
        return event.allDay()
                ? LocalDate.parse(event.start()).atStartOfDay(familyZone).toInstant()
                : Instant.parse(event.start());
    }
}
