package com.familyhub.demo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;

/**
 * The family inbox: a mailbox (a Gmail address with an app password) the server reads
 * over IMAP and replies from over SMTP, on behalf of one family. Off until the address,
 * its password and the family are all set.
 */
@ConfigurationProperties(prefix = "inbox")
@Configuration
@Data
public class InboxProperties {
    private String imapHost = "imap.gmail.com";
    private int imapPort = 993;
    private boolean imapSsl = true;
    private String smtpHost = "smtp.gmail.com";
    private int smtpPort = 465;
    private boolean smtpSsl = true;
    /** The mailbox's address, which is also its sign-in name. */
    private String username;
    /** Gmail's app password for the mailbox (never the account's own password). */
    private String password;
    /** The mailbox's address for replies' From; the username when not set. */
    private String address;
    /** The username of the family the inbox belongs to. */
    private String familyUsername;
    /**
     * The receiving servers whose Authentication-Results are believed: the mailbox's own
     * (Gmail's mx.google.com), which adds its verdict on top of every message.
     */
    private List<String> trustedAuthservIds = List.of("mx.google.com");
    /** At most this many messages are handled per reading, so one reading stays short. */
    private int maxPerPoll = 20;
    private String processedLabel = "FamilyHub-Processed";
    private String unrecognisedLabel = "FamilyHub-Unrecognised";
    private String unprocessedLabel = "FamilyHub-Unprocessed";
    /**
     * The Claude gateway that reads flyers and recipes (claude-gateway/ in this
     * repository, e.g. http://claude-gateway:8080); without it only photos are taken.
     */
    private String readerUrl;
    private String readerModel = "claude-opus-5-5";
    private String readerEffort = "high";
    /** Longer than the gateway's own limit (4 minutes), so it answers TIMEOUT first. */
    private Duration readerTimeout = Duration.ofMinutes(5);
    private Duration connectTimeout = Duration.ofSeconds(15);
    private Duration readTimeout = Duration.ofSeconds(60);

    /** The address replies are sent from. */
    public String fromAddress() {
        return notBlank(address) ? address : username;
    }

    /** Whether there is a mailbox to read. */
    public boolean configured() {
        return notBlank(username) && notBlank(password) && notBlank(familyUsername);
    }

    private static boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
