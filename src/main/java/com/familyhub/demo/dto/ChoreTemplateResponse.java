package com.familyhub.demo.dto;

import com.familyhub.demo.model.ChoreCadence;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.DayOfWeek;
import java.util.List;
import java.util.UUID;

public record ChoreTemplateResponse(
        UUID id,
        String title,
        UUID assignedToMemberId,
        ChoreCadence cadence,
        List<DayOfWeek> daysOfWeek,
        LocalDate activeFrom,
        boolean archived,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        /** Points a parent set (1 to 100); null for chores made before points. */
        Integer points
) {

    /** The shape before points existed. */
    public ChoreTemplateResponse(UUID id, String title, UUID assignedToMemberId, ChoreCadence cadence,
                                 List<DayOfWeek> daysOfWeek, LocalDate activeFrom, boolean archived,
                                 LocalDateTime createdAt, LocalDateTime updatedAt) {
        this(id, title, assignedToMemberId, cadence, daysOfWeek, activeFrom, archived, createdAt, updatedAt, null);
    }
}
