package com.familyhub.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

/** Sets or changes a parent's own PIN, confirmed with the family password. */
public record MemberPinRequest(
        @NotBlank(message = "Enter the family password")
        String password,

        @NotBlank(message = "Enter a PIN")
        @Pattern(regexp = "\\d{4}", message = "A PIN is 4 digits")
        String pin
) {
}
