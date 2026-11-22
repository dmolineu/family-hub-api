package com.familyhub.demo.service;

import com.familyhub.demo.config.InboxProperties;
import com.familyhub.demo.dto.calendar.CalendarEventResponse;
import com.familyhub.demo.dto.calendar.CalendarWriteRequest;
import com.familyhub.demo.model.CalendarEvent;
import com.familyhub.demo.model.GoogleEvent;
import com.familyhub.demo.model.GoogleEventStatus;
import com.familyhub.demo.repository.CalendarEventRepository;
import com.familyhub.demo.repository.GoogleCalendarRepository;
import com.familyhub.demo.repository.GoogleEventRepository;
import com.familyhub.demo.dto.InboxApproveRequest;
import com.familyhub.demo.dto.InboxImportResponse;
import com.familyhub.demo.dto.InboxProposal;
import com.familyhub.demo.dto.InboxSenderRequest;
import com.familyhub.demo.dto.InboxSenderResponse;
import com.familyhub.demo.dto.InboxStatusResponse;
import com.familyhub.demo.dto.ParentPinRequest;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.inbox.MailReader;
import com.familyhub.demo.inbox.MailReading;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.InboxImport;
import com.familyhub.demo.model.InboxImportKind;
import com.familyhub.demo.model.InboxImportStatus;
import com.familyhub.demo.model.InboxSender;
import com.familyhub.demo.repository.FamilyMemberRepository;
import com.familyhub.demo.repository.FamilyRepository;
import com.familyhub.demo.repository.InboxImportRepository;
import com.familyhub.demo.repository.InboxSenderRepository;
import com.familyhub.demo.scheduler.InboxPoller;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Instant;
import java.util.Set;
import java.time.ZoneId;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A parent's side of the family inbox: what it did and what waits, approving a proposed
 * event (as read or corrected), turning one down, undoing an added one, and choosing
 * which other addresses may send. Every change takes a parent's PIN; a parent's reply
 * to the inbox's email decides through {@link #add} and {@link #turnDown} instead, and
 * corrects what was added through {@link #change} and {@link #takeOff}.
 */
@Slf4j
@Service
public class InboxReviewService {
    private static final Pattern ADDRESS = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final InboxProperties properties;
    private final FamilyRepository families;
    private final FamilyMemberRepository members;
    private final InboxImportRepository imports;
    private final InboxSenderRepository senders;
    private final ParentPinService parentPinService;
    private final CalendarWriteService calendarWrites;
    private final GoogleCalendarRepository calendars;
    private final GoogleEventRepository cachedEvents;
    private final CalendarEventRepository legacyEvents;
    private final PhotoService photoService;
    private final RecipeService recipes;
    private final MailReader reader;
    private final InboxPoller poller;
    private final ObjectMapper json;
    private final Clock clock;

    public InboxReviewService(InboxProperties properties, FamilyRepository families, FamilyMemberRepository members,
                              InboxImportRepository imports, InboxSenderRepository senders,
                              ParentPinService parentPinService, CalendarWriteService calendarWrites,
                              GoogleCalendarRepository calendars, GoogleEventRepository cachedEvents,
                              CalendarEventRepository legacyEvents,
                              PhotoService photoService, RecipeService recipes, MailReader reader,
                              @Lazy InboxPoller poller, ObjectMapper json, Clock clock) {
        this.properties = properties;
        this.families = families;
        this.members = members;
        this.imports = imports;
        this.senders = senders;
        this.parentPinService = parentPinService;
        this.calendarWrites = calendarWrites;
        this.calendars = calendars;
        this.cachedEvents = cachedEvents;
        this.legacyEvents = legacyEvents;
        this.photoService = photoService;
        this.recipes = recipes;
        this.reader = reader;
        this.poller = poller;
        this.json = json;
        this.clock = clock;
    }

    /** The inbox at a glance, for anyone in the family. */
    @Transactional(readOnly = true)
    public InboxStatusResponse status(Family principal) {
        Family family = managed(principal);
        InboxPoller.Status reading = poller.status();
        List<String> memberAddresses = members.findByFamily(family).stream().map(FamilyMember::getEmail)
                .filter(Objects::nonNull).map(email -> email.trim().toLowerCase(Locale.ROOT))
                .filter(email -> !email.isEmpty()).distinct().toList();
        // A mailbox that cannot be read matters most; otherwise why flyers are waiting, if they are.
        String lastError = reading.lastError() != null ? reading.lastError() : reader.problem().orElse(null);
        return new InboxStatusResponse(reading.configured(), reading.configured() ? properties.fromAddress() : null,
                reader.available(), reading.lastReadAt(), lastError, memberAddresses,
                senders.findByFamilyOrderByAddress(family).stream()
                        .map(sender -> new InboxSenderResponse(sender.getId(), sender.getAddress())).toList(),
                imports.countByFamilyAndStatus(family, InboxImportStatus.PENDING),
                imports.findTop100ByFamilyOrderByCreatedAtDesc(family).stream().map(this::toResponse).toList());
    }

    /**
     * Puts a proposed event on the calendar, with any corrections the parent made.
     *
     * @throws ResourceNotFoundException for an import that is not the family's (404)
     * @throws BadRequestException       when it is not waiting, or still lacks a date or time (400)
     */
    @Transactional
    public InboxImportResponse approve(Family principal, UUID id, InboxApproveRequest request) {
        Family family = managed(principal);
        FamilyMember parent = parentPinService.verify(family, request.approverMemberId(), request.pin());
        InboxImport proposal = waiting(family, id);
        MailReading.ReadEvent read = readEvent(proposal);
        MailReading.ReadEvent corrected = new MailReading.ReadEvent(
                request.title() != null && !request.title().isBlank() ? request.title() : read.title(),
                request.date() != null ? request.date().toString() : read.date(),
                request.endDate() != null ? request.endDate().toString() : read.endDate(),
                request.allDay() != null ? request.allDay() : read.allDay(),
                request.startTime() != null ? request.startTime() : read.startTime(),
                request.endTime() != null ? request.endTime() : read.endTime(),
                read.location(), read.people(), read.confidence(), read.uncertainty());
        add(family, proposal, corrected, request.memberId(), parent);
        log.info("Inbox proposal approved, importId={}, familyId={}", id, family.getId());
        return toResponse(imports.save(proposal));
    }

    /**
     * Puts a waiting event on the calendar as given, decided by this parent: the app's
     * approval after the PIN, and a parent's emailed reply after its verified address.
     * The caller saves the import.
     *
     * @param memberId who it is for, overriding the event's people; null keeps them
     * @throws BadRequestException when it still lacks a date or, unless all day, a start time
     */
    void add(Family family, InboxImport proposal, MailReading.ReadEvent corrected, UUID memberId,
             FamilyMember parent) {
        List<FamilyMember> familyMembers = members.findByFamily(family);
        CalendarWriteRequest event = InboxEventMapper.toRequest(corrected, familyMembers,
                        proposal.getMessage().getSender(), proposal.getMessage().getSubject(), zone(family))
                .orElseThrow(() -> new BadRequestException("Fill in the date and, unless it lasts all day, the"
                        + " start time first."));
        if (memberId != null) {
            event = new CalendarWriteRequest(null, List.of(memberId), event.title(), event.description(),
                    event.location(), event.allDay(), event.start(), event.end(), event.startDate(), event.endDate(),
                    event.timeZone(), null, false, null);
        }
        CalendarEventResponse created = calendarWrites.create(family, event);
        proposal.setTitle(event.title());
        proposal.setGoogleCalendarId(created.calendarId());
        proposal.setGoogleEventId(created.googleEventId());
        decide(proposal, InboxImportStatus.ADDED, parent);
    }

    /**
     * Applies a parent's emailed correction to an event the inbox added, keeping what a
     * correction does not speak to (the event's description, any repeat rule). The caller
     * saves the import.
     *
     * @return false when the correction leaves the event as it is
     * @throws BadRequestException       when the correction lacks a date or, unless all day, a start time
     * @throws ResourceNotFoundException when the event is no longer on the calendar
     */
    boolean change(Family family, InboxImport added, MailReading.ReadEvent corrected, FamilyMember parent) {
        List<FamilyMember> familyMembers = members.findByFamily(family);
        String sender = added.getMessage().getSender();
        String subject = added.getMessage().getSubject();
        ZoneId zone = zone(family);
        CalendarWriteRequest wanted = InboxEventMapper.toRequest(corrected, familyMembers, sender, subject, zone)
                .orElseThrow(() -> new BadRequestException("A correction needs the date and, unless it lasts all"
                        + " day, the start time."));
        GoogleEvent existing = cached(added).orElseThrow(() -> legacyWaiting(added)
                ? new BadRequestException("It can be changed once Google Calendar is connected again.")
                : new ResourceNotFoundException("Calendar Event", added.getId()));
        // Compared as calendar requests, so "2:15 PM" read back from the calendar equals the reader's.
        boolean same = InboxEventMapper.toRequest(asRead(existing, added, familyMembers, zone), familyMembers, sender,
                subject, zone).filter(wanted::equals).isPresent();
        if (same) {
            return false;
        }
        // Keeps what a correction does not speak to: the description and any repeat rule.
        calendarWrites.update(family, existing.getId(), new CalendarWriteRequest(null, wanted.memberIds(),
                wanted.title(), existing.getDescription(), wanted.location(), wanted.allDay(), wanted.start(),
                wanted.end(), wanted.startDate(), wanted.endDate(), wanted.timeZone(), null, false, existing.getEtag()));
        added.setTitle(wanted.title());
        added.setDetails(toJson(corrected));
        decide(added, InboxImportStatus.ADDED, parent);
        return true;
    }

    /**
     * An event the inbox added, as it is on the calendar now (a parent may have edited it
     * in the app since), with any question the reader asked about it; empty once it is no
     * longer there.
     */
    Optional<MailReading.ReadEvent> onCalendar(Family family, InboxImport added) {
        if (added.getKind() != InboxImportKind.EVENT || added.getStatus() != InboxImportStatus.ADDED) {
            return Optional.empty();
        }
        List<FamilyMember> familyMembers = members.findByFamily(family);
        Optional<GoogleEvent> row = cached(added);
        if (row.isPresent()) {
            return Optional.of(asRead(row.get(), added, familyMembers, zone(family)));
        }
        // Added before Phase 4 and not copied to Google yet: read from the hub's own table.
        return legacy(added).map(event -> asRead(event, added, familyMembers));
    }

    /** The cached Google event an import points at, by Google's ids. */
    private Optional<GoogleEvent> cached(InboxImport added) {
        if (added.getGoogleCalendarId() == null || added.getGoogleEventId() == null) {
            return Optional.empty();
        }
        return calendars.findById(added.getGoogleCalendarId())
                .flatMap(calendar -> cachedEvents.findByCalendarAndGoogleEventId(calendar, added.getGoogleEventId()))
                .filter(row -> row.getStatus() != GoogleEventStatus.CANCELLED);
    }

    /** The hub's own pre-Phase 4 event an import points at, while it waits to be copied to Google. */
    private Optional<CalendarEvent> legacy(InboxImport added) {
        return added.getCalendarEventId() == null ? Optional.empty()
                : legacyEvents.findById(added.getCalendarEventId()).filter(event -> event.getCopiedAt() == null);
    }

    private boolean legacyWaiting(InboxImport added) {
        return added.getGoogleEventId() == null && legacy(added).isPresent();
    }

    private static ZoneId zone(Family family) {
        return ZoneId.of(FamilyTimezoneResolver.resolveStoredTimezoneOrDefault(family.getTimezone()));
    }

    private static final java.time.format.DateTimeFormatter TWELVE_HOUR =
            java.time.format.DateTimeFormatter.ofPattern("h:mm a", java.util.Locale.ENGLISH);

    /** A cached Google event in the reader's terms, in the family's zone, for its members. */
    private MailReading.ReadEvent asRead(GoogleEvent event, InboxImport added, List<FamilyMember> familyMembers,
                                         ZoneId zone) {
        Set<UUID> owners = event.getMemberIds() == null ? Set.of() : Set.of(event.getMemberIds());
        List<String> people = familyMembers.stream().filter(member -> owners.contains(member.getId()))
                .map(FamilyMember::getName).toList();
        String uncertainty = readEvent(added).uncertainty();
        String date;
        String endDate = "";
        String start = "";
        String end = "";
        if (event.isAllDay()) {
            date = event.getStartDate().toString();
            LocalDate last = event.getEndDate().minusDays(1);
            endDate = last.isAfter(event.getStartDate()) ? last.toString() : "";
        } else {
            date = event.getStartAt().atZone(zone).toLocalDate().toString();
            start = event.getStartAt().atZone(zone).format(TWELVE_HOUR);
            end = event.getEndAt().atZone(zone).format(TWELVE_HOUR);
        }
        return new MailReading.ReadEvent(event.getTitle(), date, endDate, event.isAllDay(), start, end,
                event.getLocation() == null ? "" : event.getLocation(), people, confidence(added),
                uncertainty == null ? "" : uncertainty);
    }

    /** One of the hub's own pre-Phase 4 events in the reader's terms. */
    private MailReading.ReadEvent asRead(CalendarEvent event, InboxImport added, List<FamilyMember> familyMembers) {
        List<String> people = familyMembers.stream().filter(member -> member.getId().equals(event.getMember().getId()))
                .map(FamilyMember::getName).toList();
        String uncertainty = readEvent(added).uncertainty();
        return new MailReading.ReadEvent(event.getTitle(), event.getDate().toString(),
                event.getEndDate() == null ? "" : event.getEndDate().toString(), event.isAllDay(),
                event.isAllDay() ? "" : event.getStartTime().format(TWELVE_HOUR),
                event.isAllDay() ? "" : event.getEndTime().format(TWELVE_HOUR),
                event.getLocation() == null ? "" : event.getLocation(), people, confidence(added),
                uncertainty == null ? "" : uncertainty);
    }

    private static MailReading.Confidence confidence(InboxImport item) {
        try {
            return item.getConfidence() == null ? null : MailReading.Confidence.valueOf(item.getConfidence());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private String toJson(MailReading.ReadEvent event) {
        try {
            return json.writeValueAsString(event);
        } catch (JacksonException e) {
            return null;
        }
    }

    /** Turns a waiting event down, decided by this parent (in the app or by email). The caller saves it. */
    void turnDown(InboxImport proposal, FamilyMember parent) {
        decide(proposal, InboxImportStatus.REJECTED, parent);
    }

    /** Turns a proposed event down. */
    @Transactional
    public InboxImportResponse reject(Family principal, UUID id, ParentPinRequest request) {
        Family family = managed(principal);
        FamilyMember parent = parentPinService.verify(family, request.approverMemberId(), request.pin());
        InboxImport proposal = waiting(family, id);
        turnDown(proposal, parent);
        return toResponse(imports.save(proposal));
    }

    /** Takes off something the inbox added. */
    @Transactional
    public InboxImportResponse undo(Family principal, UUID id, ParentPinRequest request) {
        Family family = managed(principal);
        FamilyMember parent = parentPinService.verify(family, request.approverMemberId(), request.pin());
        InboxImport added = imports.findByIdAndFamily(id, family)
                .orElseThrow(() -> new ResourceNotFoundException("Inbox import", id));
        if (added.getStatus() != InboxImportStatus.ADDED) {
            throw new BadRequestException("Only something the inbox added can be taken off.");
        }
        takeOff(family, added);
        added.setDecidedBy(parent.getId());
        return toResponse(imports.save(added));
    }

    /**
     * Removes what an import added from the family's data and marks it undone; already
     * removed by hand counts as removed. Shared with the inbox's UNDO replies.
     */
    void takeOff(Family family, InboxImport added) {
        try {
            if (added.getKind() == InboxImportKind.EVENT) {
                Optional<GoogleEvent> row = cached(added);
                if (row.isPresent()) {
                    calendarWrites.delete(family, row.get().getId(), null);
                } else {
                    // Not copied to Google yet: it only lives in the hub's own table.
                    legacy(added).ifPresent(legacyEvents::delete);
                }
            } else if (added.getKind() == InboxImportKind.PHOTO && added.getPhotoId() != null) {
                photoService.delete(family, added.getPhotoId());
            } else if (added.getKind() == InboxImportKind.RECIPE && added.getRecipeId() != null) {
                recipes.deleteRecipe(added.getRecipeId(), family);
            }
        } catch (RuntimeException e) {
            log.info("Inbox undo found it already gone, importId={}: {}", added.getId(), e.toString());
        }
        added.setStatus(InboxImportStatus.UNDONE);
        added.setDecidedAt(Instant.now(clock));
    }

    /**
     * Lets another address send to the inbox (stored lower case; adding one twice keeps one).
     *
     * @throws BadRequestException for something that is not an email address (400)
     */
    @Transactional
    public InboxSenderResponse addSender(Family principal, InboxSenderRequest request) {
        Family family = managed(principal);
        parentPinService.verify(family, request.approverMemberId(), request.pin());
        String address = request.address().trim().toLowerCase(Locale.ROOT);
        if (!ADDRESS.matcher(address).matches()) {
            throw new BadRequestException("That does not look like an email address.");
        }
        InboxSender sender = senders.findByFamilyAndAddress(family, address).orElseGet(() -> {
            InboxSender created = new InboxSender();
            created.setFamily(family);
            created.setAddress(address);
            created.setCreatedAt(Instant.now(clock));
            return senders.save(created);
        });
        return new InboxSenderResponse(sender.getId(), sender.getAddress());
    }

    /** Stops an added address from sending to the inbox. */
    @Transactional
    public void removeSender(Family principal, UUID id, ParentPinRequest request) {
        Family family = managed(principal);
        parentPinService.verify(family, request.approverMemberId(), request.pin());
        senders.delete(senders.findByIdAndFamily(id, family)
                .orElseThrow(() -> new ResourceNotFoundException("Inbox sender", id)));
    }

    private InboxImport waiting(Family family, UUID id) {
        InboxImport proposal = imports.findByIdAndFamily(id, family)
                .orElseThrow(() -> new ResourceNotFoundException("Inbox import", id));
        if (proposal.getStatus() != InboxImportStatus.PENDING || proposal.getKind() != InboxImportKind.EVENT) {
            throw new BadRequestException("That is not waiting for a decision any more.");
        }
        return proposal;
    }

    private void decide(InboxImport proposal, InboxImportStatus status, FamilyMember parent) {
        proposal.setStatus(status);
        proposal.setDecidedAt(Instant.now(clock));
        proposal.setDecidedBy(parent.getId());
    }

    /** What the reader proposed for a waiting event, as stored with it. */
    MailReading.ReadEvent readEvent(InboxImport proposal) {
        try {
            if (proposal.getDetails() != null) {
                return json.readValue(proposal.getDetails(), MailReading.ReadEvent.class);
            }
        } catch (JacksonException e) {
            log.info("Inbox proposal unreadable, importId={}", proposal.getId());
        }
        return new MailReading.ReadEvent(proposal.getTitle(), null, "", true, "", "", "", List.of(), null, "");
    }

    private InboxImportResponse toResponse(InboxImport item) {
        InboxProposal proposal = null;
        if (item.getKind() == InboxImportKind.EVENT && item.getStatus() == InboxImportStatus.PENDING) {
            MailReading.ReadEvent read = readEvent(item);
            proposal = new InboxProposal(read.title(), read.date(), read.endDate(), read.allDay(), read.startTime(),
                    read.endTime(), read.location(), read.people() == null ? List.of() : read.people(),
                    read.uncertainty());
        }
        return new InboxImportResponse(item.getId(), item.getKind().name(), item.getStatus().name(), item.getTitle(),
                item.getConfidence(), item.getCreatedAt(), item.getDecidedAt(), item.getMessage().getSender(),
                item.getMessage().getSubject(), proposal, item.getCalendarEventId(), item.getRecipeId(),
                item.getPhotoId());
    }

    private Family managed(Family principal) {
        return families.findById(principal.getId()).orElseThrow();
    }
}
