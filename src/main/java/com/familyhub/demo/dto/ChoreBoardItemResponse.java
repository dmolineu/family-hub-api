package com.familyhub.demo.dto;

import com.familyhub.demo.model.ChoreCadence;

import java.time.LocalDateTime;
import java.time.DayOfWeek;
import java.util.List;
import java.util.UUID;

public record ChoreBoardItemResponse(
        UUID templateId,
        String title,
        ChoreCadence cadence,
        List<DayOfWeek> daysOfWeek,
        UUID assignedToMemberId,
        boolean completed,
        LocalDateTime completedAt,
        UUID completedByMemberId
) {
}
