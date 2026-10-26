package com.familyhub.demo.service;

import com.familyhub.demo.model.ChoreActivity;
import com.familyhub.demo.model.ChoreActivityKind;
import com.familyhub.demo.model.ChoreTemplate;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.repository.ChoreActivityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;

/**
 * Appends lines to the chore log. Lines are never edited or removed (except by a
 * restore, which replaces the whole log), so the log is the record of who did what.
 */
@Component
@RequiredArgsConstructor
public class ChoreActivityLog {
    private final ChoreActivityRepository choreActivityRepository;
    private final Clock clock;

    /**
     * Appends one line, copying the chore's and members' names as they are now.
     *
     * @param member the member the chore is credited to
     * @param actor  the parent who decided, or null for a plain tick or untick
     */
    public void record(Family family, ChoreTemplate template, FamilyMember member, ChoreActivityKind kind,
                       int points, FamilyMember actor, String reason, LocalDate periodStartDate) {
        ChoreActivity activity = new ChoreActivity();
        activity.setFamily(family);
        activity.setChoreTemplate(template);
        activity.setChoreTitle(template.getTitle());
        activity.setMember(member);
        activity.setMemberName(member == null ? "Someone" : member.getName());
        activity.setKind(kind);
        activity.setPoints(points);
        activity.setActor(actor);
        activity.setActorName(actor == null ? null : actor.getName());
        activity.setReason(reason == null || reason.isBlank() ? null : reason.trim());
        activity.setPeriodStartDate(periodStartDate);
        activity.setOccurredAt(Instant.now(clock));
        choreActivityRepository.save(activity);
    }
}
