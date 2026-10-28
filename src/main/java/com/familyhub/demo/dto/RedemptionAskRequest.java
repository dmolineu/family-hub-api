package com.familyhub.demo.dto;

import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/** A member asking for a reward, or taking their ask back. No PIN: kids ask for themselves. */
public record RedemptionAskRequest(
        @NotNull(message = "Choose who is asking")
        UUID memberId
) {
}
