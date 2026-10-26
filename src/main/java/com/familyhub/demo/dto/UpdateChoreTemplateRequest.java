package com.familyhub.demo.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.familyhub.demo.model.ChoreCadence;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
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

        Boolean archived,

        /** Null leaves the points unchanged. */
        @Min(value = 1, message = "Points must be between 1 and 100")
        @Max(value = 100, message = "Points must be between 1 and 100")
        Integer points
) {

    /** The shape before points existed. */
    public UpdateChoreTemplateRequest(String title, UUID assignedToMemberId, ChoreCadence cadence,
                                      Set<DayOfWeek> daysOfWeek, LocalDate activeFrom, Boolean archived) {
        this(title, assignedToMemberId, cadence, daysOfWeek, activeFrom, archived, null);
    }
}
