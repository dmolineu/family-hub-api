package com.familyhub.demo.service;

import com.familyhub.demo.config.InboxProperties;
import com.familyhub.demo.dto.PhotoResponse;
import com.familyhub.demo.inbox.MailContent;
import com.familyhub.demo.inbox.ReceivedMail;
import com.familyhub.demo.inbox.SenderVerifier;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * What the family inbox does with one message: only the family's own addresses (the
 * members' and any it added), verifiably sent, are read; a message of pictures and no
 * words adds its photos to the library without anything leaving the house. Every
 * message is recorded so it is never handled twice, and filed under a label saying how
 * it went.
 */
@Slf4j
@Service
public class InboxService {
    private static final Set<String> PICTURE_TYPES = Set.of("image/jpeg", "image/png", "image/heic", "image/heif");
    private static final int MAX_PHOTOS_PER_MESSAGE = 20;
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
    private final Clock clock;

    public InboxService(InboxProperties properties, InboxMessageRepository messages, InboxSenderRepository senders,
                        InboxImportRepository imports, FamilyMemberRepository members, PhotoService photoService,
                        PictureShrinker shrinker, HeicDecoder heicDecoder, Clock clock) {
        this.properties = properties;
        this.messages = messages;
        this.senders = senders;
        this.imports = imports;
        this.members = members;
        this.photoService = photoService;
        this.shrinker = shrinker;
        this.heicDecoder = heicDecoder;
        this.verifier = new SenderVerifier(properties.getTrustedAuthservIds());
        this.clock = clock;
    }

    /** Handles one message for the family and returns the label to file it under. */
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
            MailContent content = mail.content();
            List<MailContent.Attachment> pictures = content.attachments().stream().filter(InboxService::isPicture)
                    .toList();
            if (isPhotoMessage(mail.subject(), content, pictures)) {
                return addPhotos(family, mail, pictures);
            }
            record(family, mail, InboxOutcome.UNPROCESSED, "Has words or files to read, which needs the reader");
            return properties.getUnprocessedLabel();
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
