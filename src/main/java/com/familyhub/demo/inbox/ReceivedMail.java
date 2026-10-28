package com.familyhub.demo.inbox;

import java.util.List;

/**
 * A message in the inbox. The headers are at hand; the body and attachments are read
 * only when {@link #content()} is called, so a stranger's message is never downloaded.
 */
public interface ReceivedMail {
    String messageId();

    /** The From address, lower case. */
    String from();

    String subject();

    /** The In-Reply-To header, or null. */
    String inReplyTo();

    /** Every Authentication-Results header, topmost (the receiving server's own) first. */
    List<String> authenticationResults();

    MailContent content();
}
