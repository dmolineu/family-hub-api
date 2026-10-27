package com.familyhub.demo.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** What Family Settings needs to list a pack; the characters themselves are fetched by id. */
public record AvatarPackSummaryResponse(
        UUID id, String name, int characterCount, List<String> groups, int sizeBytes, Instant createdAt) {}
