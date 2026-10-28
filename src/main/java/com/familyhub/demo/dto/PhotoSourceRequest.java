package com.familyhub.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** A parent choosing (or, with an empty folder, clearing) the folder of existing photos. */
public record PhotoSourceRequest(
        @NotNull(message = "Choose which parent this is") UUID approverMemberId,
        @NotBlank(message = "Enter your PIN") String pin,
        @Size(max = 500, message = "That path is too long") String folder
) {
}
