package com.familyhub.demo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.familyhub.demo.model.ChoreCadence;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.time.LocalDate;
import java.time.DayOfWeek;
import java.util.Set;
import java.util.List;
import java.util.UUID;

public record CreateChoreTemplateRequest(
        @NotBlank(message = "Chore title is required")
        @Size(max = 100, message = "Chore title must be 100 characters or less")
        String title,

        /** Empty or null means "anyone". One template is created per member listed. */
        @Size(max = 20, message = "A chore can be assigned to at most 20 members at once")
        List<UUID> assignedToMemberIds,

        @NotNull
        ChoreCadence cadence,

        /** WEEKLY only: the days the chore is due; empty or null means once a week. */
        Set<DayOfWeek> daysOfWeek,

        @NotNull
        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate activeFrom
) {
}
