package com.familyhub.demo.dto;

import java.time.Instant;
import java.util.UUID;

/** What the Appearance picker needs to list a pack; the document itself is fetched by id. */
public record ThemePackSummaryResponse(UUID id, String name, boolean dark, int sizeBytes, Instant createdAt) {}
