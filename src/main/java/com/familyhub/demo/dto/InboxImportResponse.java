package com.familyhub.demo.dto;

import java.time.Instant;
import java.util.UUID;

/** One thing a message added or proposed, with the message it came from. */
public record InboxImportResponse(UUID id, String kind, String status, String title, String confidence,
                                  Instant createdAt, Instant decidedAt, String sender, String subject,
                                  InboxProposal proposal, UUID calendarEventId, UUID recipeId, UUID photoId) {
}
