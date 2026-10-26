package com.familyhub.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * A parent's decision on waiting chores: the parent picks themselves, enters their own
 * PIN and approves (points granted) or rejects (chore unticked) any number of them.
 */
public record ChoreApprovalRequest(
        @NotNull(message = "Choose which parent is approving")
        UUID approverMemberId,

        @NotBlank(message = "Enter your PIN")
        String pin,

        @Size(max = 200, message = "Approve at most 200 chores at once")
        List<UUID> approve,

        @Size(max = 200, message = "Reject at most 200 chores at once")
        List<UUID> reject,

        /** Optional note shown to the family in the log for rejected chores. */
        @Size(max = 200, message = "Keep the reason to 200 characters")
        String reason
) {
}
