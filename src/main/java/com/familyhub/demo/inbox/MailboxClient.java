package com.familyhub.demo.inbox;

import jakarta.mail.MessagingException;

import java.util.function.Function;

/** Reads the family's mailbox. */
public interface MailboxClient {
    /**
     * Hands each message in the inbox (at most {@code max}, oldest first) to
     * {@code handler}, then files it under the label the handler returns: out of the
     * inbox and into that label. A null label leaves the message in the inbox.
     *
     * @throws MessagingException when the mailbox cannot be reached or read
     */
    void processInbox(int max, Function<ReceivedMail, String> handler) throws MessagingException;
}
