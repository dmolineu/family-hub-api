package com.familyhub.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * Opens the chore log, which needs a parent's PIN. A POST rather than a GET so the PIN
 * travels in the body and never in a URL or an access log.
 */
public record ChoreActivityRequest(
        @NotNull(message = "Choose which parent is opening the log")
        UUID approverMemberId,

        @NotBlank(message = "Enter your PIN")
        String pin,

        /** Only this member's lines; null for everyone. */
        UUID memberId
) {
}
