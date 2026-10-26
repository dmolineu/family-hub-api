package com.familyhub.demo.dto;

import com.familyhub.demo.model.MemberRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Makes a member a parent or a kid. The family password is required because the login
 * is shared: anyone at the wall could otherwise make themselves a parent.
 */
public record MemberRoleRequest(
        @NotNull(message = "Choose parent or kid")
        MemberRole role,

        @NotBlank(message = "Enter the family password")
        String password
) {
}
