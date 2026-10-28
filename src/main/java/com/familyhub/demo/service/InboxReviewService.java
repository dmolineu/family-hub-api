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
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * A parent's side of the family inbox: what it did and what waits, approving a proposed
 * event (as read or corrected), turning one down, undoing an added one, and choosing
 * which other addresses may send. Every change takes a parent's PIN.
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
        return new InboxStatusResponse(reading.configured(), reading.configured() ? properties.fromAddress() : null,
                reader.available(), reading.lastReadAt(), reading.lastError(), memberAddresses,
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
        List<FamilyMember> familyMembers = members.findByFamily(family);
        CalendarEventRequest event = InboxEventMapper.toRequest(corrected, familyMembers,
                        proposal.getMessage().getSender(), proposal.getMessage().getSubject())
                .orElseThrow(() -> new BadRequestException("Fill in the date and, unless it lasts all day, the"
                        + " start time first."));
        if (request.memberId() != null) {
            event = new CalendarEventRequest(event.title(), event.startTime(), event.endTime(), event.date(),
                    request.memberId(), event.isAllDay(), event.location(), event.endDate(), event.recurrenceRule(),
                    event.description());
        }
        CalendarEventResponse created = calendarEvents.addCalendarEvent(event, family);
        proposal.setTitle(event.title());
        proposal.setCalendarEventId(created.id());
        decide(proposal, InboxImportStatus.ADDED, parent);
        log.info("Inbox proposal approved, importId={}, familyId={}", id, family.getId());
        return toResponse(imports.save(proposal));
    }

    /** Turns a proposed event down. */
    @Transactional
    public InboxImportResponse reject(Family principal, UUID id, ParentPinRequest request) {
        Family family = managed(principal);
        FamilyMember parent = parentPinService.verify(family, request.approverMemberId(), request.pin());
        InboxImport proposal = waiting(family, id);
        decide(proposal, InboxImportStatus.REJECTED, parent);
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

    private MailReading.ReadEvent readEvent(InboxImport proposal) {
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
