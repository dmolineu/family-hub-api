package com.familyhub.demo.dto;

import java.time.Instant;
import java.util.UUID;

/** A photo's details without its bytes; the image comes from /content or /thumbnail. */
public record PhotoResponse(
        UUID id,
        String contentType,
        int width,
        int height,
        int sizeBytes,
        Instant createdAt
) {
}
