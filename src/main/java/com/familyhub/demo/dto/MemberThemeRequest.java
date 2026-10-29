package com.familyhub.demo.dto;

import jakarta.validation.constraints.Size;

/**
 * The look a member's screen switches to when they are tapped: {@code builtin:<id>},
 * {@code pack:<name of one of the family's theme packs>}, or null for the screen's own.
 */
public record MemberThemeRequest(
        @Size(max = 100, message = "A theme is 100 characters or less")
        String theme
) {
}
