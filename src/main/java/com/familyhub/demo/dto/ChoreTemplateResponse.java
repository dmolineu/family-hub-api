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
        LocalDateTime updatedAt
) {
}
