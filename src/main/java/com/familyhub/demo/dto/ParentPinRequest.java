package com.familyhub.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.UUID;

/**
 * A parent naming themselves and entering their own PIN, sent in a request body so
 * the PIN never appears in a URL or an access log.
 */
public record ParentPinRequest(
        @NotNull(message = "Choose which parent this is")
        UUID approverMemberId,

        @NotBlank(message = "Enter your PIN")
        String pin
) {
}
