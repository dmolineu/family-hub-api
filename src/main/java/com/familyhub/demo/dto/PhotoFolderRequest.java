package com.familyhub.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.UUID;

/** A parent moving the family's photos to another folder, with their own PIN. */
public record PhotoFolderRequest(
        @NotNull(message = "Choose which parent this is") UUID approverMemberId,
        @NotBlank(message = "Enter your PIN") String pin,
        @NotBlank(message = "Give the folder's full path") @Size(max = 500, message = "That path is too long")
        String folder
) {
}
