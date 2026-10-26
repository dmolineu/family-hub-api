package com.familyhub.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Changing the family's sign-in password from a signed-in device. The current
 * password is required as well as the token, because the token alone only proves
 * someone is at a signed-in screen, such as the kitchen wall.
 */
public record ChangePasswordRequest(
        @NotBlank(message = "Enter the current sign-in password")
        String currentPassword,

        @NotBlank(message = "Enter a new password")
        @Size(min = 8, max = 100, message = "Password must be between 8 - 100 characters")
        String newPassword
) {
}
