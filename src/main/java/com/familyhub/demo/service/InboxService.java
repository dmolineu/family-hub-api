package com.familyhub.demo.service;

import com.familyhub.demo.config.InboxProperties;
import com.familyhub.demo.dto.CalendarEventRequest;
import com.familyhub.demo.dto.CalendarEventResponse;
import com.familyhub.demo.dto.CreateRecipeRequest;
import com.familyhub.demo.dto.ImportRecipeRequest;
import com.familyhub.demo.dto.PhotoResponse;
import com.familyhub.demo.dto.RecipeDetailResponse;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.inbox.MailContent;
import com.familyhub.demo.inbox.MailReader;
import com.familyhub.demo.inbox.MailReaderException;
import com.familyhub.demo.inbox.MailReading;
import com.familyhub.demo.inbox.ReadingRequest;
import com.familyhub.demo.inbox.ReceivedMail;
import com.familyhub.demo.inbox.Reply;
import com.familyhub.demo.inbox.ReplyReading;
import com.familyhub.demo.inbox.ReplyRequest;
import com.familyhub.demo.inbox.ReplySender;
import com.familyhub.demo.inbox.ReplyText;
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
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
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
 * happened; replying UNDO takes the added ones off again, and a parent's reply decides
 * what waits (APPROVED, NO, or what to change). Every message is recorded so it is never
 * handled twice, and filed under a label saying how it went.
 */
@Slf4j
@Service
public class InboxService {
    private static final Set<String> PICTURE_TYPES = Set.of("image/jpeg", "image/png", "image/heic", "image/heif");
    private static final int MAX_PHOTOS_PER_MESSAGE = 20;
    private static final int MAX_EVENTS_PER_MESSAGE = 40;
    /** The order an email lists waiting events in, which is what a reply's numbers refer to. */
    private static final Comparator<InboxImport> LISTED = Comparator.comparing(InboxImport::getCreatedAt)
            .thenComparing(InboxImport::getId);
    /** A reply approving all that waits, once reduced to plain words: "Approved!", "yes please". */
    private static final Pattern APPROVE = Pattern.compile(
            "(approved?|yes|ok|okay|create|add|confirm(ed)?)( (it|them|all|both|please|thanks|thank you))*");
    /** A reply turning all that waits down: "No thanks", "reject". */
    private static final Pattern TURN_DOWN = Pattern.compile(
            "(no|nope|reject(ed)?|decline|skip)( (it|them|all|both|please|thanks|thank you))*");
    /** "event" or "events" as a word of the subject, "#event" included; not "prevent" or "eventually". */
    private static final Pattern EVENT_WORD = Pattern.compile("\\bevents?\\b");
    private static final String HOW_TO_DECIDE = "A parent can reply APPROVED to add them as listed, say what to"
            + " change (\"it starts at 5:30 PM\", \"only the first one\"), or reply NO to turn them down. They are"
            + " in the app too (Family inbox).\n";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("EEE, MMM d", Locale.ENGLISH);
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
    private final RecipeService recipes;
    private final InboxReviewService review;
    private final ObjectMapper json;
    private final Clock clock;

    public InboxService(InboxProperties properties, InboxMessageRepository messages, InboxSenderRepository senders,
                        InboxImportRepository imports, FamilyMemberRepository members, PhotoService photoService,
                        PictureShrinker shrinker, HeicDecoder heicDecoder, MailReader reader, ReplySender replies,
                        CalendarEventService calendarEvents, RecipeService recipes, InboxReviewService review,
                        ObjectMapper json, Clock clock) {
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
        this.recipes = recipes;
        this.review = review;
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
                case RECIPE -> addRecipe(family, mail, reading);
                case NOTHING -> nothingToAdd(family, mail, reading);
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

    /**
     * "#photo" says so; otherwise only pictures with no words of the sender's own. A
     * subject saying "event" counts as words, with or without the "#" (user, 2026-11-01):
     * "Add event" on a shared screenshot means a flyer, not a photo for the frame.
     */
    private static boolean isPhotoMessage(String subject, MailContent content, List<MailContent.Attachment> pictures) {
        if (pictures.isEmpty()) {
            return false;
        }
        String tags = subject == null ? "" : subject.toLowerCase(Locale.ROOT);
        if (tags.contains("#photo")) {
            return true;
        }
        if (EVENT_WORD.matcher(tags).find() || tags.contains("#recipe")) {
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
        List<InboxImport> waiting = new ArrayList<>();
        for (MailReading.ReadEvent event : reading.events().subList(0,
                Math.min(MAX_EVENTS_PER_MESSAGE, reading.events().size()))) {
            InboxImport proposal = newImport(family, message, InboxImportKind.EVENT, event.title());
            proposal.setConfidence(event.confidence() == null ? null : event.confidence().name());
            Optional<CalendarEventRequest> request = InboxEventMapper.toRequest(event, familyMembers, mail.from(),
                    mail.subject());
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
                waiting.add(imports.save(proposal));
            } else {
                imports.save(proposal);
            }
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
            text.append("Waiting for a parent to check:\n").append(waitingList(waiting, familyMembers))
                    .append('\n').append(HOW_TO_DECIDE).append('\n');
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

    /**
     * A recipe joins the library: from its page when the email links one (the app's own
     * importer, which respects sites that refuse it), else from what the email itself
     * says. The sender is told, and can reply UNDO.
     */
    private String addRecipe(Family family, ReceivedMail mail, MailReading reading) {
        MailReading.ReadRecipe found = reading.recipe();
        String url = found.sourceUrl() == null ? "" : found.sourceUrl().strip();
        RecipeDetailResponse saved = null;
        if (!url.isEmpty()) {
            try {
                saved = recipes.importRecipe(new ImportRecipeRequest(url), family);
            } catch (RuntimeException e) {
                log.info("Inbox recipe page not read, familyId={}: {}", family.getId(), e.getMessage());
            }
        }
        if (saved == null && found.title() != null && !found.title().isBlank()
                && found.ingredients() != null && !found.ingredients().isEmpty()) {
            try {
                saved = recipes.createRecipe(new CreateRecipeRequest(truncate(found.title().strip(), 160), null,
                        cleaned(found.ingredients(), 500, 100), cleaned(found.steps(), 4000, 100),
                        truncate("From an email: " + mail.subject()
                                + (found.servings() == null || found.servings().isBlank() ? ""
                                : ". Serves " + found.servings().strip()), 2000),
                        url.startsWith("http://") || url.startsWith("https://") ? url : null,
                        cleaned(found.tags(), 60, 10), false), family);
            } catch (RuntimeException e) {
                log.info("Inbox recipe not saved, familyId={}: {}", family.getId(), e.getMessage());
            }
        }
        if (saved == null) {
            InboxMessage message = record(family, mail, InboxOutcome.UNPROCESSED, "A recipe that could not be read");
            message.setReplyMessageId(sendReply(family, mail, "Hi from the Family Hub. I could not read the recipe"
                    + " in \"" + mail.subject() + "\"" + (url.isEmpty() ? "" : " (" + url + ")")
                    + ". Some sites do not let other apps read their pages; pasting the ingredients and steps into"
                    + " an email works instead.\n", false));
            messages.save(message);
            return properties.getUnprocessedLabel();
        }
        InboxMessage message = record(family, mail, InboxOutcome.RECIPE, saved.title());
        InboxImport added = newImport(family, message, InboxImportKind.RECIPE, saved.title());
        added.setRecipeId(saved.id());
        imports.save(added);
        message.setReplyMessageId(sendReply(family, mail, "Hi from the Family Hub. Added to the recipe library: "
                + saved.title() + ".\n\nReply UNDO to this email to take it out again.\n", false));
        messages.save(message);
        log.info("Inbox recipe added, familyId={}, recipeId={}", family.getId(), saved.id());
        return properties.getProcessedLabel();
    }

    /** Non-blank items, each at most {@code length} characters, at most {@code count} of them. */
    private static List<String> cleaned(List<String> items, int length, int count) {
        return items == null ? List.of() : items.stream().filter(Objects::nonNull).map(String::strip)
                .filter(item -> !item.isEmpty()).map(item -> truncate(item, length)).limit(count).toList();
    }

    private String nothingToAdd(Family family, ReceivedMail mail, MailReading reading) {
        InboxMessage message = record(family, mail, InboxOutcome.UNPROCESSED, reading.summary());
        message.setReplyMessageId(sendReply(family, mail, "Hi from the Family Hub. I read \"" + mail.subject()
                + "\" but found nothing to add: no dates for the calendar. It is set aside in the inbox"
                + " under " + properties.getUnprocessedLabel() + ".\n", false));
        messages.save(message);
        return properties.getUnprocessedLabel();
    }

    /**
     * A reply to one of the inbox's emails, about the message its conversation began
     * with: UNDO takes off what that message added; from a parent, anything else decides
     * the events it left waiting (APPROVED and NO as they are, other words through the
     * reader). The verified sender address stands in for the app's PIN.
     */
    private String handleReply(Family family, ReceivedMail mail, InboxMessage answered) {
        // Every reply records the first message, so an answer to an answer still finds it.
        InboxMessage first = answered.getAnswers() != null ? answered.getAnswers() : answered;
        String words = ReplyText.ownWords(mail.content().text());
        String firstLine = words.lines().map(String::strip).filter(line -> !line.isEmpty())
                .findFirst().orElse("").toLowerCase(Locale.ROOT);
        if (firstLine.startsWith("undo")) {
            return undo(family, mail, first);
        }
        List<InboxImport> waiting = imports.findByMessage(first).stream()
                .filter(item -> item.getKind() == InboxImportKind.EVENT
                        && item.getStatus() == InboxImportStatus.PENDING)
                .sorted(LISTED).toList();
        if (waiting.isEmpty()) {
            record(family, mail, InboxOutcome.UNPROCESSED, "A reply with nothing waiting; only UNDO is understood",
                    first);
            return properties.getUnprocessedLabel();
        }
        Optional<FamilyMember> parent = parentWithAddress(family, mail.from());
        if (parent.isEmpty()) {
            return notDecided(family, mail, first, "Only a parent can decide by email",
                    "Hi from the Family Hub. Only a parent can approve or turn down events by email. A parent can"
                            + " reply to the first email, or check them in the app (Family inbox).\n", true);
        }
        String plain = words.toLowerCase(Locale.ROOT).replaceAll("[^a-z ]+", " ").strip().replaceAll(" +", " ");
        if (APPROVE.matcher(plain).matches()) {
            return decide(family, mail, first, waiting, every(waiting, ReplyReading.Action.ADD), "", parent.get());
        }
        if (TURN_DOWN.matcher(plain).matches()) {
            return decide(family, mail, first, waiting, every(waiting, ReplyReading.Action.TURN_DOWN), "",
                    parent.get());
        }
        String asApprovedOrApp = " Reply APPROVED to add them as listed or NO to turn them down, or change them in"
                + " the app (Family inbox).\n";
        if (!reader.available()) {
            return notDecided(family, mail, first, "A change asked by email, which needs the reader",
                    "Hi from the Family Hub. Reading changes needs the Claude reader, which is not set up, so"
                            + " nothing was changed." + asApprovedOrApp, false);
        }
        ReplyReading reading;
        try {
            reading = reader.readReply(new ReplyRequest(words, waiting.stream().map(review::readEvent).toList(),
                    today(family), zone(family).getId(),
                    members.findByFamily(family).stream().map(FamilyMember::getName).toList()));
        } catch (MailReaderException e) {
            if (e.temporary()) {
                log.info("Inbox reader busy; reply left for the next reading, familyId={}: {}",
                        family.getId(), e.getMessage());
                return null;
            }
            notDecided(family, mail, first, e.getMessage(), "Hi from the Family Hub. I could not read your reply,"
                    + " so nothing was changed." + asApprovedOrApp, false);
            return properties.getUnprocessedLabel();
        }
        return decide(family, mail, first, waiting, reading.decisions() == null ? List.of() : reading.decisions(),
                reading.note() == null ? "" : reading.note().strip(), parent.get());
    }

    private String undo(Family family, ReceivedMail mail, InboxMessage first) {
        List<String> takenOff = new ArrayList<>();
        for (InboxImport added : imports.findByMessage(first)) {
            if (added.getStatus() != InboxImportStatus.ADDED) {
                continue;
            }
            review.takeOff(family, added);
            imports.save(added);
            takenOff.add(added.getTitle());
        }
        InboxMessage message = record(family, mail, InboxOutcome.UNDO, takenOff.size() + " taken off", first);
        String text = takenOff.isEmpty()
                ? "Hi from the Family Hub. There was nothing left to take off.\n"
                : "Hi from the Family Hub. Taken off again:\n" + String.join("\n",
                takenOff.stream().map(title -> "  - " + title).toList()) + "\n";
        message.setReplyMessageId(sendReply(family, mail, text, true));
        messages.save(message);
        log.info("Inbox undo, familyId={}, takenOff={}", family.getId(), takenOff.size());
        return properties.getProcessedLabel();
    }

    /** A parent's decisions on what waits, applied as the app's approval would; then the parents are told. */
    private String decide(Family family, ReceivedMail mail, InboxMessage first, List<InboxImport> waiting,
                          List<ReplyReading.Decision> decisions, String note, FamilyMember parent) {
        List<FamilyMember> familyMembers = members.findByFamily(family);
        List<String> added = new ArrayList<>();
        List<String> turnedDown = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        Set<Integer> decided = new HashSet<>();
        for (ReplyReading.Decision decision : decisions) {
            // Only the numbers the email showed, each once; anything else is not a decision.
            if (decision.number() < 1 || decision.number() > waiting.size() || decision.action() == null
                    || decision.action() == ReplyReading.Action.WAIT || !decided.add(decision.number())) {
                continue;
            }
            InboxImport proposal = waiting.get(decision.number() - 1);
            MailReading.ReadEvent event = decision.event() == null ? review.readEvent(proposal) : decision.event();
            if (decision.action() == ReplyReading.Action.TURN_DOWN) {
                review.turnDown(proposal, parent);
                imports.save(proposal);
                turnedDown.add(describe(review.readEvent(proposal), familyMembers));
                continue;
            }
            try {
                review.add(family, proposal, event, null, parent);
                imports.save(proposal);
                added.add(describe(event, familyMembers));
            } catch (BadRequestException e) {
                problems.add(describe(event, familyMembers) + " could not go on the calendar: it needs a date and,"
                        + " unless it lasts all day, a start time.");
            }
        }
        List<InboxImport> stillWaiting = waiting.stream()
                .filter(proposal -> proposal.getStatus() == InboxImportStatus.PENDING).toList();
        InboxMessage message = record(family, mail, InboxOutcome.DECIDED, added.size() + " added, "
                + turnedDown.size() + " turned down, " + stillWaiting.size() + " still waiting", first);

        StringBuilder text = new StringBuilder("Hi from the Family Hub.\n\n");
        if (!added.isEmpty()) {
            text.append("Added to the calendar:\n");
            added.forEach(line -> text.append("  - ").append(line).append('\n'));
            text.append('\n');
        }
        if (!turnedDown.isEmpty()) {
            text.append("Turned down:\n");
            turnedDown.forEach(line -> text.append("  - ").append(line).append('\n'));
            text.append('\n');
        }
        problems.forEach(problem -> text.append(problem).append('\n'));
        if (!note.isEmpty()) {
            text.append(note).append('\n');
        }
        if (!problems.isEmpty() || !note.isEmpty()) {
            text.append('\n');
        }
        if (!stillWaiting.isEmpty()) {
            if (added.isEmpty() && turnedDown.isEmpty() && problems.isEmpty() && note.isEmpty()) {
                text.append("Nothing was changed: the reply did not say what to do with them.\n\n");
            }
            text.append("Still waiting for a parent to check:\n").append(waitingList(stillWaiting, familyMembers))
                    .append('\n').append(HOW_TO_DECIDE).append('\n');
        }
        if (!added.isEmpty()) {
            text.append("Reply UNDO to this email to take the added events off the calendar.\n");
        }
        message.setReplyMessageId(sendReply(family, mail, text.toString(), true));
        messages.save(message);
        log.info("Inbox reply decided, familyId={}, added={}, turnedDown={}, waiting={}", family.getId(),
                added.size(), turnedDown.size(), stillWaiting.size());
        return properties.getProcessedLabel();
    }

    /** A reply that decided nothing, recorded, with the reason told to its sender. */
    private String notDecided(Family family, ReceivedMail mail, InboxMessage first, String detail, String text,
                              boolean copyParents) {
        InboxMessage message = record(family, mail, InboxOutcome.UNPROCESSED, detail, first);
        message.setReplyMessageId(sendReply(family, mail, text, copyParents));
        messages.save(message);
        return properties.getUnprocessedLabel();
    }

    private static List<ReplyReading.Decision> every(List<InboxImport> waiting, ReplyReading.Action action) {
        List<ReplyReading.Decision> decisions = new ArrayList<>();
        for (int number = 1; number <= waiting.size(); number++) {
            decisions.add(new ReplyReading.Decision(number, action, null));
        }
        return decisions;
    }

    /** The family's parent with this address (Gmail has already verified it sent the message). */
    private Optional<FamilyMember> parentWithAddress(Family family, String address) {
        return members.findByFamily(family).stream()
                .filter(member -> member.getRole() == MemberRole.PARENT && member.getEmail() != null
                        && member.getEmail().trim().equalsIgnoreCase(address))
                .findFirst();
    }

    /** Waiting events numbered as a reply refers to them, each with why it waits. */
    private String waitingList(List<InboxImport> waiting, List<FamilyMember> familyMembers) {
        StringBuilder list = new StringBuilder();
        List<InboxImport> listed = waiting.stream().sorted(LISTED).toList();
        for (int i = 0; i < listed.size(); i++) {
            InboxImport proposal = listed.get(i);
            MailReading.ReadEvent event = review.readEvent(proposal);
            boolean readable = InboxEventMapper.toRequest(event, familyMembers, proposal.getMessage().getSender(),
                    proposal.getMessage().getSubject()).isPresent();
            String why = event.uncertainty() == null || event.uncertainty().isBlank()
                    ? (readable ? "" : "the date or time could not be read") : event.uncertainty();
            list.append("  ").append(i + 1).append(". ").append(describe(event, familyMembers))
                    .append(why.isBlank() ? "" : " (" + why + ")").append('\n');
        }
        return list.toString();
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
        return record(family, mail, outcome, detail, null);
    }

    /** Records a message; {@code answers} is the message a reply's conversation began with. */
    private InboxMessage record(Family family, ReceivedMail mail, InboxOutcome outcome, String detail,
                                InboxMessage answers) {
        InboxMessage message = new InboxMessage();
        message.setAnswers(answers);
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
