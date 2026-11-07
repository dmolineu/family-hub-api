package com.familyhub.demo.service;

import com.familyhub.demo.config.InboxProperties;
import com.familyhub.demo.dto.CalendarEventRequest;
import com.familyhub.demo.dto.CalendarEventResponse;
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
    private final CalendarEventService calendarEvents;
    private final PhotoService photoService;
    private final RecipeService recipes;
    private final MailReader reader;
    private final InboxPoller poller;
    private final ObjectMapper json;
    private final Clock clock;

    public InboxReviewService(InboxProperties properties, FamilyRepository families, FamilyMemberRepository members,
                              InboxImportRepository imports, InboxSenderRepository senders,
                              ParentPinService parentPinService, CalendarEventService calendarEvents,
                              PhotoService photoService, RecipeService recipes, MailReader reader,
                              @Lazy InboxPoller poller, ObjectMapper json, Clock clock) {
        this.properties = properties;
        this.families = families;
        this.members = members;
        this.imports = imports;
        this.senders = senders;
        this.parentPinService = parentPinService;
        this.calendarEvents = calendarEvents;
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
        CalendarEventRequest event = InboxEventMapper.toRequest(corrected, familyMembers,
                        proposal.getMessage().getSender(), proposal.getMessage().getSubject())
                .orElseThrow(() -> new BadRequestException("Fill in the date and, unless it lasts all day, the"
                        + " start time first."));
        if (memberId != null) {
            event = new CalendarEventRequest(event.title(), event.startTime(), event.endTime(), event.date(),
                    memberId, event.isAllDay(), event.location(), event.endDate(), event.recurrenceRule(),
                    event.description());
        }
        CalendarEventResponse created = calendarEvents.addCalendarEvent(event, family);
        proposal.setTitle(event.title());
        proposal.setCalendarEventId(created.id());
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
        CalendarEventRequest wanted = InboxEventMapper.toRequest(corrected, familyMembers, sender, subject)
                .orElseThrow(() -> new BadRequestException("A correction needs the date and, unless it lasts all"
                        + " day, the start time."));
        CalendarEventResponse existing = calendarEvents.getEventById(added.getCalendarEventId(), family);
        // Compared as calendar requests, so "2:15 PM" read back from the calendar equals the reader's.
        boolean same = InboxEventMapper.toRequest(asRead(existing, added, familyMembers), familyMembers, sender,
                subject).filter(wanted::equals).isPresent();
        if (same) {
            return false;
        }
        calendarEvents.updateCalendarEvent(new CalendarEventRequest(wanted.title(), wanted.startTime(),
                wanted.endTime(), wanted.date(), wanted.memberId(), wanted.isAllDay(), wanted.location(),
                wanted.endDate(), existing.recurrenceRule(), existing.description()), existing.id(), family);
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
        if (added.getKind() != InboxImportKind.EVENT || added.getStatus() != InboxImportStatus.ADDED
                || added.getCalendarEventId() == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(asRead(calendarEvents.getEventById(added.getCalendarEventId(), family), added,
                    members.findByFamily(family)));
        } catch (ResourceNotFoundException e) {
            return Optional.empty();
        }
    }

    /** A calendar event in the reader's terms, for its member and with the import's confidence and question. */
    private MailReading.ReadEvent asRead(CalendarEventResponse event, InboxImport added,
                                         List<FamilyMember> familyMembers) {
        List<String> people = familyMembers.stream().filter(member -> member.getId().equals(event.memberId()))
                .map(FamilyMember::getName).toList();
        String uncertainty = readEvent(added).uncertainty();
        return new MailReading.ReadEvent(event.title(), event.date().toString(),
                event.endDate() == null ? "" : event.endDate().toString(), event.isAllDay(),
                event.isAllDay() ? "" : event.startTime(), event.isAllDay() ? "" : event.endTime(),
                event.location() == null ? "" : event.location(), people, confidence(added),
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
            if (added.getKind() == InboxImportKind.EVENT && added.getCalendarEventId() != null) {
                calendarEvents.deleteCalendarEvent(added.getCalendarEventId(), family);
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
