package com.familyhub.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** A parent allowing another address (a grandparent, a coach) to send to the inbox. */
public record InboxSenderRequest(
        @NotNull(message = "Choose which parent this is") UUID approverMemberId,
        @NotBlank(message = "Enter your PIN") String pin,
        @NotBlank(message = "Enter an email address") @Size(max = 320) String address
) {
}
