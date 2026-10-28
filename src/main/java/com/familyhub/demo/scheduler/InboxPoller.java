package com.familyhub.demo.scheduler;

import com.familyhub.demo.config.InboxProperties;
import com.familyhub.demo.inbox.MailboxClient;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.repository.FamilyRepository;
import com.familyhub.demo.service.InboxService;
import jakarta.mail.MessagingException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Reads the family inbox every couple of minutes, once it is configured. One reading
 * at a time: a scheduled one skips while another runs.
 */
@Slf4j
@Component
public class InboxPoller {
    private final InboxProperties properties;
    private final MailboxClient mailbox;
    private final InboxService inbox;
    private final FamilyRepository families;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock();
    private volatile Instant lastReadAt;
    private volatile String lastError;

    /** How the last reading went. */
    public record Status(boolean configured, Instant lastReadAt, String lastError) {}

    public InboxPoller(InboxProperties properties, MailboxClient mailbox, InboxService inbox,
                       FamilyRepository families, Clock clock) {
        this.properties = properties;
        this.mailbox = mailbox;
        this.inbox = inbox;
        this.families = families;
        this.clock = clock;
    }

    @Scheduled(fixedDelayString = "${inbox.poll-interval:PT2M}", initialDelayString = "${inbox.initial-delay:PT1M}")
    public void scheduledPoll() {
        if (lock.tryLock()) {
            try {
                poll();
            } finally {
                lock.unlock();
            }
        }
    }

    /** Reads the inbox now, waiting for a reading already under way. */
    public void pollNow() {
        lock.lock();
        try {
            poll();
        } finally {
            lock.unlock();
        }
    }

    public Status status() {
        return new Status(properties.configured(), lastReadAt, lastError);
    }

    private void poll() {
        if (!properties.configured()) {
            return;
        }
        Optional<Family> family = families.findByUsername(properties.getFamilyUsername());
        if (family.isEmpty()) {
            lastError = "No family named " + properties.getFamilyUsername();
            log.warn("Inbox not read: no family named {}", properties.getFamilyUsername());
            return;
        }
        try {
            mailbox.processInbox(properties.getMaxPerPoll(), mail -> inbox.handle(family.get(), mail));
            lastReadAt = Instant.now(clock);
            lastError = null;
        } catch (MessagingException | RuntimeException e) {
            lastError = "The mailbox could not be read: " + e.getMessage();
            log.warn("Inbox could not be read: {}", e.toString());
        }
    }
}
