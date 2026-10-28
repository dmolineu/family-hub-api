package com.familyhub.demo.service;

import com.familyhub.demo.config.InboxProperties;
import com.familyhub.demo.dto.CalendarEventRequest;
import com.familyhub.demo.dto.CalendarEventResponse;
import com.familyhub.demo.dto.PhotoResponse;
import com.familyhub.demo.inbox.MailContent;
import com.familyhub.demo.inbox.MailReader;
import com.familyhub.demo.inbox.MailReaderException;
import com.familyhub.demo.inbox.MailReading;
import com.familyhub.demo.inbox.ReadingRequest;
import com.familyhub.demo.inbox.ReceivedMail;
import com.familyhub.demo.inbox.Reply;
import com.familyhub.demo.inbox.ReplySender;
import com.familyhub.demo.inbox.SenderVerifier;
import com.familyhub.demo.model.MemberRole;
import jakarta.mail.MessagingException;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.InboxImport;
import com.familyhub.demo.model.InboxImportKind;
import com.familyhub.demo.model.InboxImportStatus;
import com.familyhub.demo.model.InboxMessage;
import com.familyhub.demo.model.InboxOutcome;
import com.familyhub.demo.repository.FamilyMemberRepository;
import com.familyhub.demo.repository.InboxImportRepository;
import com.familyhub.demo.repository.InboxMessageRepository;
import com.familyhub.demo.repository.InboxSenderRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What the family inbox does with one message: only the family's own addresses (the
 * members' and any it added), verifiably sent, are read; a message of pictures and no
 * words adds its photos to the library without anything leaving the house. Anything
 * with words goes to the reader: events it is sure of go straight on the calendar,
 * the rest wait for a parent, and the sender (with both parents copied) is told what
 * happened; replying UNDO takes the added ones off again. Every message is recorded so
 * it is never handled twice, and filed under a label saying how it went.
 */
@Slf4j
@Service
public class InboxService {
    private static final Set<String> PICTURE_TYPES = Set.of("image/jpeg", "image/png", "image/heic", "image/heif");
    private static final int MAX_PHOTOS_PER_MESSAGE = 20;
    private static final int MAX_EVENTS_PER_MESSAGE = 40;
    private static final Pattern TIME = Pattern.compile("^(1[0-2]|[1-9]):[0-5][0-9] (AM|PM)$");
    private static final DateTimeFormatter TWELVE_HOUR = DateTimeFormatter.ofPattern("h:mm a", Locale.ENGLISH);
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH);
    private static final String ALL_DAY_TIME = "12:00 AM";
    /** Lines phones and mail apps add to every message, which are not the sender's words. */
    private static final Pattern SIGNATURE = Pattern.compile(
            "(?im)^\\s*(sent from my .*|sent from (yahoo )?mail.*|get outlook for .*|sent via .*)\\s*$");

    private final InboxProperties properties;
    private final InboxMessageRepository messages;
    private final InboxSenderRepository senders;
    private final InboxImportRepository imports;
    private final FamilyMemberRepository members;
    private final PhotoService photoService;
    private final PictureShrinker shrinker;
    private final HeicDecoder heicDecoder;
    private final SenderVerifier verifier;
    private final MailReader reader;
    private final ReplySender replies;
    private final CalendarEventService calendarEvents;
    private final ObjectMapper json;
    private final Clock clock;

    public InboxService(InboxProperties properties, InboxMessageRepository messages, InboxSenderRepository senders,
                        InboxImportRepository imports, FamilyMemberRepository members, PhotoService photoService,
                        PictureShrinker shrinker, HeicDecoder heicDecoder, MailReader reader, ReplySender replies,
                        CalendarEventService calendarEvents, ObjectMapper json, Clock clock) {
        this.properties = properties;
        this.messages = messages;
        this.senders = senders;
        this.imports = imports;
        this.members = members;
        this.photoService = photoService;
        this.shrinker = shrinker;
        this.heicDecoder = heicDecoder;
        this.verifier = new SenderVerifier(properties.getTrustedAuthservIds());
        this.reader = reader;
        this.replies = replies;
        this.calendarEvents = calendarEvents;
        this.json = json;
        this.clock = clock;
    }

    /**
     * Handles one message for the family and returns the label to file it under, or
     * null to leave it in the inbox for the next reading (the reader was busy).
     */
    public String handle(Family family, ReceivedMail mail) {
        var earlier = messages.findByFamilyAndMessageId(family, mail.messageId());
        if (earlier.isPresent()) {
            return labelFor(earlier.get().getOutcome());
        }
        if (!allowed(family, mail)) {
            record(family, mail, InboxOutcome.UNRECOGNISED,
                    "Not from an address the family allows, or not verifiably sent from it");
            log.info("Inbox message not from the family, familyId={}", family.getId());
            return properties.getUnrecognisedLabel();
        }
        try {
            Optional<InboxMessage> answered = mail.inReplyTo() == null ? Optional.empty()
                    : messages.findByFamilyAndReplyMessageId(family, mail.inReplyTo());
            if (answered.isPresent()) {
                return handleReply(family, mail, answered.get());
            }
            MailContent content = mail.content();
            List<MailContent.Attachment> pictures = content.attachments().stream().filter(InboxService::isPicture)
                    .toList();
            if (isPhotoMessage(mail.subject(), content, pictures)) {
                return addPhotos(family, mail, pictures);
            }
            if (!reader.available()) {
                record(family, mail, InboxOutcome.UNPROCESSED, "Has words or files to read, which needs the reader");
                return properties.getUnprocessedLabel();
            }
            MailReading reading;
            try {
                reading = reader.read(new ReadingRequest(mail.subject(), content.text(), content.attachments(),
                        today(family), zone(family).getId(), members.findByFamily(family).stream()
                        .map(FamilyMember::getName).toList()));
            } catch (MailReaderException e) {
                if (e.temporary()) {
                    log.info("Inbox reader busy; message left for the next reading, familyId={}: {}",
                            family.getId(), e.getMessage());
                    return null;
                }
                record(family, mail, InboxOutcome.FAILED, e.getMessage());
                return properties.getUnprocessedLabel();
            }
            return switch (reading.kind()) {
                case EVENTS -> addEvents(family, mail, reading);
                case RECIPE, NOTHING -> nothingToAdd(family, mail, reading);
            };
        } catch (RuntimeException e) {
            log.warn("Inbox message failed, familyId={}: {}", family.getId(), e.toString());
            record(family, mail, InboxOutcome.FAILED, e.getMessage());
            return properties.getUnprocessedLabel();
        }
    }

    private boolean allowed(Family family, ReceivedMail mail) {
        String from = mail.from();
        if (from == null || from.isBlank()) {
            return false;
        }
        boolean known = members.findByFamily(family).stream().map(FamilyMember::getEmail).filter(Objects::nonNull)
                .anyMatch(email -> email.trim().equalsIgnoreCase(from))
                || senders.findByFamilyAndAddress(family, from).isPresent();
        return known && verifier.verified(from, mail.authenticationResults());
    }

    /** "#photo" says so; otherwise only pictures with no words of the sender's own. */
    private static boolean isPhotoMessage(String subject, MailContent content, List<MailContent.Attachment> pictures) {
        if (pictures.isEmpty()) {
            return false;
        }
        String tags = subject == null ? "" : subject.toLowerCase(Locale.ROOT);
        if (tags.contains("#photo")) {
            return true;
        }
        if (tags.contains("#event") || tags.contains("#recipe")) {
            return false;
        }
        boolean onlyPictures = pictures.size() == content.attachments().size();
        return onlyPictures && SIGNATURE.matcher(content.text()).replaceAll("").isBlank();
    }

    private String addPhotos(Family family, ReceivedMail mail, List<MailContent.Attachment> pictures) {
        InboxMessage message = record(family, mail, InboxOutcome.PHOTOS, null);
        int added = 0;
        List<String> problems = new ArrayList<>();
        for (MailContent.Attachment picture : pictures.subList(0, Math.min(MAX_PHOTOS_PER_MESSAGE, pictures.size()))) {
            try {
                byte[] readable = isHeic(picture) ? decodeHeic(picture) : picture.bytes();
                PhotoResponse saved = photoService.upload(family, shrinker.forScreen(readable));
                InboxImport photo = newImport(family, message, InboxImportKind.PHOTO, picture.fileName());
                photo.setPhotoId(saved.id());
                imports.save(photo);
                added++;
            } catch (IOException | RuntimeException e) {
                problems.add(picture.fileName() + ": " + e.getMessage());
            }
        }
        if (pictures.size() > MAX_PHOTOS_PER_MESSAGE) {
            problems.add("only the first " + MAX_PHOTOS_PER_MESSAGE + " photos are taken from one message");
        }
        message.setDetail(truncate(added + " photo(s) added" + (problems.isEmpty() ? "" : "; " + problems)));
        if (added == 0) {
            message.setOutcome(InboxOutcome.FAILED);
        }
        messages.save(message);
        log.info("Inbox photos added, familyId={}, added={}, problems={}", family.getId(), added, problems.size());
        return added == 0 ? properties.getUnprocessedLabel() : properties.getProcessedLabel();
    }

    /** Confident events go on the calendar; the rest wait for a parent. Then the sender is told. */
    private String addEvents(Family family, ReceivedMail mail, MailReading reading) {
        InboxMessage message = record(family, mail, InboxOutcome.EVENTS, reading.summary());
        List<FamilyMember> familyMembers = members.findByFamily(family);
        List<String> added = new ArrayList<>();
        List<String> waiting = new ArrayList<>();
        for (MailReading.ReadEvent event : reading.events().subList(0,
                Math.min(MAX_EVENTS_PER_MESSAGE, reading.events().size()))) {
            InboxImport proposal = newImport(family, message, InboxImportKind.EVENT, event.title());
            proposal.setConfidence(event.confidence() == null ? null : event.confidence().name());
            Optional<CalendarEventRequest> request = toRequest(event, familyMembers, mail);
            boolean wasAdded = false;
            if (event.confidence() == MailReading.Confidence.HIGH && request.isPresent()) {
                try {
                    CalendarEventResponse created = calendarEvents.addCalendarEvent(request.get(), family);
                    proposal.setCalendarEventId(created.id());
                    added.add(describe(event, familyMembers));
                    wasAdded = true;
                } catch (RuntimeException e) {
                    log.info("Inbox event could not be added, familyId={}: {}", family.getId(), e.toString());
                }
            }
            if (!wasAdded) {
                proposal.setStatus(InboxImportStatus.PENDING);
                proposal.setDetails(toJson(event));
                String why = event.uncertainty() == null || event.uncertainty().isBlank()
                        ? (request.isEmpty() ? "the date or time could not be read" : "")
                        : event.uncertainty();
                waiting.add(describe(event, familyMembers) + (why.isBlank() ? "" : " (" + why + ")"));
            }
            imports.save(proposal);
        }
        message.setDetail(truncate(added.size() + " event(s) added, " + waiting.size() + " waiting for review"));
        StringBuilder text = new StringBuilder("Hi from the Family Hub. I read \"")
                .append(mail.subject()).append("\".\n\n");
        if (!added.isEmpty()) {
            text.append("Added to the calendar:\n");
            added.forEach(line -> text.append("  - ").append(line).append('\n'));
            text.append('\n');
        }
        if (!waiting.isEmpty()) {
            text.append("Waiting for a parent to check in the app (Family inbox):\n");
            waiting.forEach(line -> text.append("  - ").append(line).append('\n'));
            text.append('\n');
        }
        if (added.isEmpty() && waiting.isEmpty()) {
            text.append("I found no events in it that are still to come.\n\n");
        }
        if (!added.isEmpty()) {
            text.append("Reply UNDO to this email to take the added events off the calendar.\n");
        }
        message.setReplyMessageId(sendReply(family, mail, text.toString(), true));
        messages.save(message);
        log.info("Inbox events read, familyId={}, added={}, waiting={}", family.getId(), added.size(), waiting.size());
        return properties.getProcessedLabel();
    }

    private String nothingToAdd(Family family, ReceivedMail mail, MailReading reading) {
        InboxMessage message = record(family, mail, InboxOutcome.UNPROCESSED, reading.summary());
        message.setReplyMessageId(sendReply(family, mail, "Hi from the Family Hub. I read \"" + mail.subject()
                + "\" but found nothing to add: no dates for the calendar. It is set aside in the inbox"
                + " under " + properties.getUnprocessedLabel() + ".\n", false));
        messages.save(message);
        return properties.getUnprocessedLabel();
    }

    /** A reply to one of the inbox's confirmations: UNDO takes off what that message added. */
    private String handleReply(Family family, ReceivedMail mail, InboxMessage answered) {
        String firstLine = mail.content().text().lines().map(String::strip).filter(line -> !line.isEmpty())
                .findFirst().orElse("").toLowerCase(Locale.ROOT);
        if (!firstLine.startsWith("undo")) {
            record(family, mail, InboxOutcome.UNPROCESSED, "A reply to a confirmation; only UNDO is understood");
            return properties.getUnprocessedLabel();
        }
        List<String> takenOff = new ArrayList<>();
        for (InboxImport added : imports.findByMessage(answered)) {
            if (added.getStatus() != InboxImportStatus.ADDED) {
                continue;
            }
            try {
                if (added.getKind() == InboxImportKind.EVENT && added.getCalendarEventId() != null) {
                    calendarEvents.deleteCalendarEvent(added.getCalendarEventId(), family);
                } else if (added.getKind() == InboxImportKind.PHOTO && added.getPhotoId() != null) {
                    photoService.delete(family, added.getPhotoId());
                }
            } catch (RuntimeException e) {
                // Already removed by hand: nothing left to take off.
                log.info("Inbox undo found it already gone, importId={}: {}", added.getId(), e.toString());
            }
            added.setStatus(InboxImportStatus.UNDONE);
            added.setDecidedAt(Instant.now(clock));
            imports.save(added);
            takenOff.add(added.getTitle());
        }
        InboxMessage message = record(family, mail, InboxOutcome.UNDO, takenOff.size() + " taken off");
        String text = takenOff.isEmpty()
                ? "Hi from the Family Hub. There was nothing left to take off.\n"
                : "Hi from the Family Hub. Taken off the calendar:\n" + String.join("\n",
                takenOff.stream().map(title -> "  - " + title).toList()) + "\n";
        message.setReplyMessageId(sendReply(family, mail, text, true));
        messages.save(message);
        log.info("Inbox undo, familyId={}, takenOff={}", family.getId(), takenOff.size());
        return properties.getProcessedLabel();
    }

    /**
     * Replies to the sender, copying both parents when asked, and returns the reply's
     * Message-ID; a reply that cannot be sent is logged and does not undo the work.
     */
    private String sendReply(Family family, ReceivedMail mail, String text, boolean copyParents) {
        List<String> cc = copyParents ? members.findByFamily(family).stream()
                .filter(member -> member.getRole() == MemberRole.PARENT)
                .map(FamilyMember::getEmail).filter(Objects::nonNull).map(String::trim)
                .filter(email -> !email.isEmpty() && !email.equalsIgnoreCase(mail.from()))
                .distinct().toList() : List.of();
        String subject = mail.subject().regionMatches(true, 0, "re:", 0, 3) ? mail.subject() : "Re: " + mail.subject();
        try {
            return replies.send(new Reply(mail.from(), cc, subject, mail.messageId(), text));
        } catch (MessagingException | RuntimeException e) {
            log.warn("Inbox reply not sent, familyId={}: {}", family.getId(), e.toString());
            return null;
        }
    }

    /** The calendar request for an event, if its date and times can be read. */
    private Optional<CalendarEventRequest> toRequest(MailReading.ReadEvent event, List<FamilyMember> familyMembers,
                                                     ReceivedMail mail) {
        try {
            String title = event.title() == null ? "" : event.title().strip();
            if (title.isEmpty()) {
                return Optional.empty();
            }
            LocalDate date = LocalDate.parse(event.date());
            LocalDate endDate = event.allDay() && event.endDate() != null && !event.endDate().isBlank()
                    ? LocalDate.parse(event.endDate()) : null;
            String start = ALL_DAY_TIME;
            String end = ALL_DAY_TIME;
            if (!event.allDay()) {
                if (event.startTime() == null || !TIME.matcher(event.startTime()).matches()) {
                    return Optional.empty();
                }
                start = event.startTime();
                end = event.endTime() != null && TIME.matcher(event.endTime()).matches() ? event.endTime()
                        : LocalTime.parse(start, TWELVE_HOUR).plusHours(1).format(TWELVE_HOUR);
                if (!LocalTime.parse(start, TWELVE_HOUR).isBefore(LocalTime.parse(end, TWELVE_HOUR))) {
                    return Optional.empty();
                }
            }
            FamilyMember member = memberFor(event, familyMembers, mail.from());
            if (member == null) {
                return Optional.empty();
            }
            String location = event.location() == null || event.location().isBlank() ? null
                    : truncate(event.location().strip(), 255);
            return Optional.of(new CalendarEventRequest(truncate(title, 100), start, end, date, member.getId(),
                    event.allDay(), location, endDate, null, truncate("From an email: " + mail.subject(), 2000)));
        } catch (DateTimeParseException e) {
            return Optional.empty();
        }
    }

    /** The first person named, else the sender, else a parent, else anyone in the family. */
    private static FamilyMember memberFor(MailReading.ReadEvent event, List<FamilyMember> familyMembers, String from) {
        if (event.people() != null) {
            for (String name : event.people()) {
                for (FamilyMember member : familyMembers) {
                    if (member.getName() != null && member.getName().equalsIgnoreCase(name.strip())) {
                        return member;
                    }
                }
            }
        }
        return familyMembers.stream().filter(member -> member.getEmail() != null
                        && member.getEmail().trim().equalsIgnoreCase(from)).findFirst()
                .or(() -> familyMembers.stream().filter(member -> member.getRole() == MemberRole.PARENT).findFirst())
                .or(() -> familyMembers.stream().findFirst())
                .orElse(null);
    }

    private static String describe(MailReading.ReadEvent event, List<FamilyMember> familyMembers) {
        StringBuilder line = new StringBuilder(event.title() == null ? "(untitled)" : event.title()).append(": ");
        try {
            line.append(LocalDate.parse(event.date()).format(DAY));
        } catch (DateTimeParseException | NullPointerException e) {
            line.append(event.date());
        }
        if (event.allDay()) {
            line.append(", all day");
        } else if (event.startTime() != null && !event.startTime().isBlank()) {
            line.append(", ").append(event.startTime());
            if (event.endTime() != null && !event.endTime().isBlank()) {
                line.append("–").append(event.endTime());
            }
        }
        if (event.people() != null && !event.people().isEmpty()) {
            line.append(" (").append(String.join(", ", event.people())).append(")");
        }
        return line.toString();
    }

    private String toJson(MailReading.ReadEvent event) {
        try {
            return json.writeValueAsString(event);
        } catch (JacksonException e) {
            return null;
        }
    }

    private LocalDate today(Family family) {
        return LocalDate.now(clock.withZone(zone(family)));
    }

    private static ZoneId zone(Family family) {
        try {
            return ZoneId.of(FamilyTimezoneResolver.resolveStoredTimezoneOrDefault(family.getTimezone()));
        } catch (RuntimeException e) {
            return ZoneId.of("America/Los_Angeles");
        }
    }

    private byte[] decodeHeic(MailContent.Attachment picture) throws IOException {
        if (!heicDecoder.available()) {
            throw new IOException("this server cannot decode HEIC photos");
        }
        Path file = Files.createTempFile("inbox-", ".heic");
        try {
            Files.write(file, picture.bytes());
            return heicDecoder.toJpeg(file);
        } finally {
            Files.deleteIfExists(file);
        }
    }

    private InboxMessage record(Family family, ReceivedMail mail, InboxOutcome outcome, String detail) {
        InboxMessage message = new InboxMessage();
        message.setFamily(family);
        message.setMessageId(truncate(mail.messageId(), 998));
        message.setSender(truncate(mail.from(), 320));
        message.setSubject(truncate(mail.subject(), 998));
        message.setOutcome(outcome);
        message.setDetail(truncate(detail));
        message.setProcessedAt(Instant.now(clock));
        return messages.save(message);
    }

    private InboxImport newImport(Family family, InboxMessage message, InboxImportKind kind, String title) {
        InboxImport created = new InboxImport();
        created.setFamily(family);
        created.setMessage(message);
        created.setKind(kind);
        created.setStatus(InboxImportStatus.ADDED);
        created.setTitle(truncate(title == null || title.isBlank() ? kind.name().toLowerCase(Locale.ROOT) : title, 200));
        created.setCreatedAt(Instant.now(clock));
        return created;
    }

    private String labelFor(InboxOutcome outcome) {
        return switch (outcome) {
            case UNRECOGNISED -> properties.getUnrecognisedLabel();
            case UNPROCESSED, FAILED -> properties.getUnprocessedLabel();
            default -> properties.getProcessedLabel();
        };
    }

    private static boolean isPicture(MailContent.Attachment attachment) {
        return PICTURE_TYPES.contains(attachment.contentType()) || isHeic(attachment);
    }

    private static boolean isHeic(MailContent.Attachment attachment) {
        String name = attachment.fileName() == null ? "" : attachment.fileName().toLowerCase(Locale.ROOT);
        return attachment.contentType().equals("image/heic") || attachment.contentType().equals("image/heif")
                || name.endsWith(".heic") || name.endsWith(".heif");
    }

    private static String truncate(String value) {
        return truncate(value, 1000);
    }

    private static String truncate(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max - 1) + "…";
    }
}
