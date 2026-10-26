package com.familyhub.demo.dto;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/** A kid's ticked chore waiting for a parent. */
public record PendingChoreApprovalResponse(
        UUID completionId,
        UUID choreTemplateId,
        String choreTitle,
        UUID memberId,
        String memberName,
        LocalDateTime completedAt,
        LocalDate periodStartDate,
        LocalDate periodEndDate,
        /** Null for a chore made before points existed; approving it grants none. */
        Integer points
) {
}
