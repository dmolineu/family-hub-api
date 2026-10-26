package com.familyhub.demo.service;

import com.familyhub.demo.model.ChoreCompletionStatus;
import com.familyhub.demo.model.ChorePeriodCompletion;
import com.familyhub.demo.model.ChoreTemplate;
import com.familyhub.demo.repository.ChorePeriodCompletionRepository;
import com.familyhub.demo.repository.ChoreTemplateRepository;
import com.familyhub.demo.repository.FamilyMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Inserts a completion in its own transaction, so a duplicate tick from another device
 * (a unique-constraint violation) can be caught without poisoning the caller's.
 */
@Service
@RequiredArgsConstructor
public class ChorePeriodCompletionWriter {
    private final ChorePeriodCompletionRepository chorePeriodCompletionRepository;
    private final ChoreTemplateRepository choreTemplateRepository;
    private final FamilyMemberRepository familyMemberRepository;

    /**
     * Saves a completion.
     *
     * @param status        PENDING for a kid's chore, APPROVED otherwise
     * @param pointsAwarded points granted now (approved), or null while pending
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public ChorePeriodCompletion createCompletion(
            UUID templateId,
            LocalDate periodStartDate,
            LocalDate periodEndDate,
            LocalDateTime completedAt,
            UUID completedByMemberId,
            ChoreCompletionStatus status,
            Integer pointsAwarded
    ) {
        ChoreTemplate template = choreTemplateRepository.getReferenceById(templateId);

        ChorePeriodCompletion completion = new ChorePeriodCompletion();
        completion.setChoreTemplate(template);
        completion.setPeriodStartDate(periodStartDate);
        completion.setPeriodEndDate(periodEndDate);
        completion.setCompletedAt(completedAt);
        completion.setCompletedByMember(
                completedByMemberId == null ? null : familyMemberRepository.getReferenceById(completedByMemberId)
        );

        completion.setStatus(status);
        completion.setPointsAwarded(pointsAwarded);

        return chorePeriodCompletionRepository.saveAndFlush(completion);
    }
}
