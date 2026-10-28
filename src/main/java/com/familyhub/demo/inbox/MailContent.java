package com.familyhub.demo.inbox;

import java.util.List;

/**
 * A message's readable text (plain text, or HTML reduced to text) and its attachments,
 * including inline pictures.
 */
public record MailContent(String text, List<Attachment> attachments) {

    /** One attached file; {@code contentType} is lower case without parameters. */
    public record Attachment(String fileName, String contentType, byte[] bytes) {}
}
