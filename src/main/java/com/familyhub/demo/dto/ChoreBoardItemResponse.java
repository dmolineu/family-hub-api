package com.familyhub.demo.dto;

import com.familyhub.demo.model.ChoreCadence;
import com.familyhub.demo.model.ChoreCompletionStatus;

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
        UUID completedByMemberId,
        /** PENDING while a kid's chore waits for a parent, APPROVED once it counts; null if not done. */
        ChoreCompletionStatus status,
        /** The chore's points; null for a chore made before points existed. */
        Integer points
) {

    /** The shape before approvals: done chores count as approved, no points. */
    public ChoreBoardItemResponse(UUID templateId, String title, ChoreCadence cadence, List<DayOfWeek> daysOfWeek,
                                  UUID assignedToMemberId, boolean completed, LocalDateTime completedAt,
                                  UUID completedByMemberId) {
        this(templateId, title, cadence, daysOfWeek, assignedToMemberId, completed, completedAt,
                completedByMemberId, completed ? ChoreCompletionStatus.APPROVED : null, null);
    }
}
