package com.familyhub.demo.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.util.UUID;

/**
 * A parent approving a proposed event, optionally with corrections; any field left out
 * keeps what the inbox read.
 */
public record InboxApproveRequest(
        @NotNull(message = "Choose which parent this is") UUID approverMemberId,
        @NotBlank(message = "Enter your PIN") String pin,
        @Size(max = 100, message = "Event title must be 100 characters or less") String title,
        LocalDate date,
        LocalDate endDate,
        Boolean allDay,
        String startTime,
        String endTime,
        UUID memberId
) {
}
