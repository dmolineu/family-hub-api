package com.familyhub.demo.service;

import com.familyhub.demo.google.GoogleCalendarException;
import com.familyhub.demo.google.GoogleCalendarGateway;
import com.familyhub.demo.google.GoogleEventCacheMapper;
import com.familyhub.demo.google.MemberAttribution;
import com.familyhub.demo.model.CalendarEvent;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.GoogleCalendar;
import com.familyhub.demo.model.GoogleCalendarAccess;
import com.familyhub.demo.model.GoogleEvent;
import com.familyhub.demo.model.GoogleOAuthToken;
import com.familyhub.demo.model.InboxImport;
import com.familyhub.demo.repository.CalendarEventRepository;
import com.familyhub.demo.repository.FamilyMemberRepository;
import com.familyhub.demo.repository.FamilyRepository;
import com.familyhub.demo.repository.GoogleCalendarAccessRepository;
import com.familyhub.demo.repository.GoogleCalendarRepository;
import com.familyhub.demo.repository.GoogleEventRepository;
import com.familyhub.demo.repository.InboxImportRepository;
import com.google.api.services.calendar.model.Event;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Clock;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Copies the family's own events (made before Phase 4, inbox events among them) to its
 * default Google calendar, once an account may write there (Phase 4). Each lands once:
 * Google is first asked for an earlier copy carrying the row's id, so a run cut off after
 * Google took an event never makes a second one. Each row is marked in its own
 * transaction, so a failure keeps what was done. Inbox records follow their events.
 */
@Slf4j
@Service
public class LegacyEventBackfill {

    /** The shared extended property naming the hub row an event was copied from. */
    public static final String LEGACY_ID_PROPERTY = LegacyEventConverter.LEGACY_ID_PROPERTY;

    private final CalendarEventRepository legacy;
    private final FamilyRepository families;
    private final FamilyMemberRepository members;
    private final GoogleCalendarRepository calendars;
    private final GoogleCalendarAccessRepository access;
    private final GoogleEventRepository events;
    private final InboxImportRepository imports;
    private final GoogleCalendarGateway gateway;
    private final LegacyEventConverter converter;
    private final GoogleEventCacheMapper mapper;
    private final MemberAttribution attribution;
    private final Clock clock;
    private final TransactionTemplate transactions;

    /**
     * Creates the backfill.
     *
     * @param legacy             the hub's own events
     * @param families           families, for the default calendar and zone
     * @param members            family members, for attribution
     * @param calendars          cached calendars
     * @param access             accounts' roles on them
     * @param events             cached events
     * @param imports            inbox records, re-pointed at the copies
     * @param gateway            Google
     * @param converter          hub row to Google event
     * @param mapper             Google event to cache row
     * @param attribution        whose lanes an event goes in
     * @param clock              the time, for copied_at
     * @param transactionManager marks each copy in its own transaction
     */
    public LegacyEventBackfill(CalendarEventRepository legacy, FamilyRepository families,
                               FamilyMemberRepository members, GoogleCalendarRepository calendars,
                               GoogleCalendarAccessRepository access, GoogleEventRepository events,
                               InboxImportRepository imports, GoogleCalendarGateway gateway,
                               LegacyEventConverter converter, GoogleEventCacheMapper mapper,
                               MemberAttribution attribution, Clock clock,
                               PlatformTransactionManager transactionManager) {
        this.legacy = legacy;
        this.families = families;
        this.members = members;
        this.calendars = calendars;
        this.access = access;
        this.events = events;
        this.imports = imports;
        this.gateway = gateway;
        this.converter = converter;
        this.mapper = mapper;
        this.attribution = attribution;
        this.clock = clock;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /** Copies what is waiting for every family; one family's failure never stops the others. */
    public void copyAll() {
        for (UUID familyId : legacy.findFamiliesWithUncopied()) {
            try {
                copyFamily(familyId);
            } catch (RuntimeException e) {
                log.error("Copying a family's own events to Google failed, familyId={}", familyId, e);
            }
        }
    }

    /**
     * Copies one family's waiting events to its default calendar.
     *
     * @param familyId the family's id
     * @return how many events (single events and series) were copied
     */
    public int copyFamily(UUID familyId) {
        Family family = families.findById(familyId).orElse(null);
        if (family == null || family.getDefaultGoogleCalendarId() == null) {
            return 0;
        }
        Optional<GoogleCalendar> target = calendars.findForSync(family.getDefaultGoogleCalendarId())
                .filter(GoogleCalendar::isEnabled);
        Optional<GoogleOAuthToken> account = target.flatMap(calendar -> access.findByCalendarId(calendar.getId())
                .stream()
                .filter(GoogleCalendarAdminService::canWrite)
                .min(Comparator.comparing(GoogleCalendarAccess::getAccessRole))
                .map(GoogleCalendarAccess::getToken));
        List<CalendarEvent> waiting = legacy.findUncopied(familyId);
        if (waiting.isEmpty()) {
            return 0;
        }
        if (target.isEmpty() || account.isEmpty()) {
            log.info("The family's own events wait for an account that may write to its default calendar, "
                    + "familyId={}, waiting={}", familyId, waiting.size());
            return 0;
        }
        ZoneId zone = ZoneId.of(FamilyTimezoneResolver.resolveStoredTimezoneOrDefault(family.getTimezone()));
        Map<UUID, List<CalendarEvent>> exceptions = new HashMap<>();
        List<CalendarEvent> heads = new ArrayList<>();
        for (CalendarEvent row : waiting) {
            if (row.getRecurringEvent() == null) {
                heads.add(row);
            } else {
                exceptions.computeIfAbsent(row.getRecurringEvent().getId(), id -> new ArrayList<>()).add(row);
            }
        }
        int copied = 0;
        for (CalendarEvent row : heads) {
            try {
                copyOne(account.get(), target.get(), row, exceptions.getOrDefault(row.getId(), List.of()), zone);
                copied++;
            } catch (GoogleCalendarException e) {
                log.warn("Could not copy an event to Google, familyId={}, eventId={}, kind={}: {}", familyId,
                        row.getId(), e.getKind(), e.getMessage());
                if (e.getKind() != GoogleCalendarException.Kind.REJECTED) {
                    // Google is down, slowing us, or refusing the account: the next run tries again.
                    break;
                }
            }
        }
        log.info("Copied the family's own events to Google, familyId={}, copied={}, waiting={}", familyId, copied,
                heads.size() - copied);
        return copied;
    }

    private void copyOne(GoogleOAuthToken account, GoogleCalendar calendar, CalendarEvent row,
                         List<CalendarEvent> rowExceptions, ZoneId zone) {
        String calendarId = calendar.getGoogleCalendarId();
        Event master = gateway.listEvents(account, calendarId,
                        GoogleCalendarGateway.ListRequest.withSharedProperty(LEGACY_ID_PROPERTY, row.getId().toString()))
                .items().stream().filter(e -> !"cancelled".equals(e.getStatus())).findFirst()
                .orElseGet(() -> gateway.insertEvent(account, calendarId, converter.toGoogle(row, rowExceptions, zone),
                        false));
        Map<UUID, Event> changedDates = new HashMap<>();
        for (CalendarEvent exception : rowExceptions) {
            if (exception.isCancelled()) {
                continue;
            }
            Event instance = gateway.getInstance(account, calendarId, master.getId(),
                    converter.originalStart(row, exception, zone));
            Event edited = converter.applyException(instance, exception, zone);
            changedDates.put(exception.getId(),
                    gateway.updateEvent(account, calendarId, instance.getId(), edited, instance.getEtag(), false));
        }
        transactions.executeWithoutResult(status -> {
            GoogleCalendar managed = calendars.findForSync(calendar.getId()).orElseThrow();
            List<FamilyMember> familyMembers = members.findByFamily(managed.getFamily());
            cache(managed, master, familyMembers);
            changedDates.values().forEach(instance -> cache(managed, instance, familyMembers));
            List<UUID> ids = new ArrayList<>();
            ids.add(row.getId());
            rowExceptions.forEach(exception -> ids.add(exception.getId()));
            for (CalendarEvent copied : legacy.findAllById(ids)) {
                Event copy = changedDates.getOrDefault(copied.getId(), master);
                copied.setCopiedCalendarId(managed.getId());
                copied.setCopiedGoogleEventId(copy.getId());
                copied.setCopiedAt(clock.instant());
            }
            for (InboxImport record : imports.findByCalendarEventIdIn(Set.copyOf(ids))) {
                Event copy = changedDates.getOrDefault(record.getCalendarEventId(), master);
                record.setGoogleCalendarId(managed.getId());
                record.setGoogleEventId(copy.getId());
            }
        });
    }

    private void cache(GoogleCalendar calendar, Event event, List<FamilyMember> familyMembers) {
        GoogleEvent row = events.findByCalendarAndGoogleEventId(calendar, event.getId()).orElseGet(GoogleEvent::new);
        UUID[] memberIds = attribution.membersOf(event, calendar, familyMembers).toArray(UUID[]::new);
        events.save(mapper.toCache(event, calendar, memberIds, row));
    }
}
