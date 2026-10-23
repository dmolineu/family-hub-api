package com.familyhub.demo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.familyhub.demo.model.ChoreCadence;
import jakarta.validation.constraints.Size;

import java.time.DayOfWeek;
import java.util.Set;
import java.time.LocalDate;
import java.util.UUID;

public record UpdateChoreTemplateRequest(
        @Size(max = 100, message = "Chore title must be 100 characters or less")
        String title,

        UUID assignedToMemberId,

        ChoreCadence cadence,

        /** Null leaves the days unchanged; an empty set clears them. */
        Set<DayOfWeek> daysOfWeek,

        @JsonFormat(pattern = "yyyy-MM-dd")
        LocalDate activeFrom,

        Boolean archived
) {
}
