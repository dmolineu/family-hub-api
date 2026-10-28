package com.familyhub.demo.inbox;

import java.time.LocalDate;
import java.util.List;

/** A message to read, with what the reader needs to place its dates and people. */
public record ReadingRequest(String subject, String text, List<MailContent.Attachment> attachments,
                             LocalDate today, String timeZone, List<String> memberNames) {
}
