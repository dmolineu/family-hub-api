package com.familyhub.demo.inbox;

import jakarta.mail.MessagingException;

/** Sends the inbox's replies. */
public interface ReplySender {
    /**
     * Sends the reply and returns its Message-ID, so a reply to it can be recognised.
     *
     * @throws MessagingException when it could not be sent
     */
    String send(Reply reply) throws MessagingException;
}
