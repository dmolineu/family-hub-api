package com.familyhub.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/** A parent approving (points spent) or declining (points freed) waiting asks, with their own PIN. */
public record RedemptionDecisionRequest(
        @NotNull(message = "Choose which parent is deciding")
        UUID approverMemberId,

        @NotBlank(message = "Enter your PIN")
        String pin,

        @Size(max = 200, message = "Approve at most 200 rewards at once")
        List<UUID> approve,

        @Size(max = 200, message = "Decline at most 200 rewards at once")
        List<UUID> decline,

        /** Optional note shown in the log for declined asks. */
        @Size(max = 200, message = "Keep the reason to 200 characters")
        String reason
) {
}
