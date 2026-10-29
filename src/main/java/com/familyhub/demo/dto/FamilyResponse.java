package com.familyhub.demo.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record FamilyResponse(
        UUID id,
        String name,
        String timezone,
        List<FamilyMemberResponse> members,
        LocalDateTime createdAt,
        String weatherLocation,
        /** Resolved from the weather location; null until one is set. Devices use them for local sunrise/sunset. */
        Double latitude,
        Double longitude,
        /** Minutes untouched before a screen leaves someone's own look; 0 for never. */
        int themeRevertMinutes
) {
    /** The shape before members had their own look. */
    public FamilyResponse(UUID id, String name, String timezone, List<FamilyMemberResponse> members,
                          LocalDateTime createdAt, String weatherLocation, Double latitude, Double longitude) {
        this(id, name, timezone, members, createdAt, weatherLocation, latitude, longitude, 0);
    }
}
