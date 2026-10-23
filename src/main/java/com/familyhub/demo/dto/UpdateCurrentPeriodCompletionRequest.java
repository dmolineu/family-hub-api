package com.familyhub.demo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.familyhub.demo.model.ChoreScope;
import jakarta.validation.constraints.NotNull;

import java.time.LocalDate;
import java.util.UUID;

public record UpdateCurrentPeriodCompletionRequest(
        @NotNull
        ChoreScope scope,

        @NotNull
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate periodStartDate,

        /** Required for an unassigned chore; defaults to the assignee otherwise. */
        UUID completedByMemberId
) {
}
