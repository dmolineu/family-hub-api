package com.familyhub.demo.dto;

import com.familyhub.demo.model.ChoreActivity;
import com.familyhub.demo.model.ChoreActivityKind;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/** One line of the chore log. */
public record ChoreActivityResponse(
        UUID id,
        ChoreActivityKind kind,
        UUID choreTemplateId,
        String choreTitle,
        UUID memberId,
        String memberName,
        int points,
        UUID actorMemberId,
        String actorName,
        String reason,
        LocalDate periodStartDate,
        Instant occurredAt
) {

    /** Maps a log line; references to deleted members or chores come back null. */
    public static ChoreActivityResponse toDto(ChoreActivity activity) {
        return new ChoreActivityResponse(
                activity.getId(),
                activity.getKind(),
                activity.getChoreTemplate() == null ? null : activity.getChoreTemplate().getId(),
                activity.getChoreTitle(),
                activity.getMember() == null ? null : activity.getMember().getId(),
                activity.getMemberName(),
                activity.getPoints(),
                activity.getActor() == null ? null : activity.getActor().getId(),
                activity.getActorName(),
                activity.getReason(),
                activity.getPeriodStartDate(),
                activity.getOccurredAt()
        );
    }
}
