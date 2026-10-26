package com.familyhub.demo.service;

import com.familyhub.demo.dto.ChoreActivityRequest;
import com.familyhub.demo.dto.ChoreActivityResponse;
import com.familyhub.demo.dto.ChoreApprovalRequest;
import com.familyhub.demo.dto.ChoreApprovalResultResponse;
import com.familyhub.demo.dto.ChorePointsResponse;
import com.familyhub.demo.dto.ParentPinRequest;
import com.familyhub.demo.dto.PendingApprovalCountResponse;
import com.familyhub.demo.dto.PendingChoreApprovalResponse;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.model.ChoreActivity;
import com.familyhub.demo.model.ChoreActivityKind;
import com.familyhub.demo.model.ChoreCompletionStatus;
import com.familyhub.demo.model.ChorePeriodCompletion;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.repository.ChoreActivityRepository;
import com.familyhub.demo.repository.ChorePeriodCompletionRepository;
import com.familyhub.demo.repository.FamilyMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Parents approving kids' chores, the points balances that result, and the chore log.
 *
 * <p>Approving grants the chore's points; rejecting unticks the chore so it can be done
 * again. Both need the deciding parent's own PIN, and both are written to the log.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ChoreApprovalService {
    /** The log view shows the newest lines; the backup carries all of them. */
    static final int LOG_LIMIT = 500;

    private final ChorePeriodCompletionRepository chorePeriodCompletionRepository;
    private final ChoreActivityRepository choreActivityRepository;
    private final FamilyMemberRepository familyMemberRepository;
    private final ChoreActivityLog choreActivityLog;
    private final ParentPinService parentPinService;

    /** How many kids' chores wait; no details, so no PIN is needed (it drives a badge). */
    public PendingApprovalCountResponse countPending(Family family) {
        return new PendingApprovalCountResponse(
                chorePeriodCompletionRepository.countByFamilyAndStatus(family, ChoreCompletionStatus.PENDING));
    }

    /**
     * Kids' ticked chores waiting for a parent, oldest first, each with when it was
     * ticked. Seeing them needs a parent's PIN, like deciding them: the approvals
     * screen opens with one PIN and closes locked.
     *
     * @throws AccessDeniedException for a kid, a wrong PIN or a locked PIN (403)
     */
    public List<PendingChoreApprovalResponse> listPending(Family family, ParentPinRequest request) {
        parentPinService.verify(family, request.approverMemberId(), request.pin());
        return listPending(family);
    }

    private List<PendingChoreApprovalResponse> listPending(Family family) {
        return chorePeriodCompletionRepository.findByFamilyAndStatus(family, ChoreCompletionStatus.PENDING)
                .stream()
                .map(completion -> new PendingChoreApprovalResponse(
                        completion.getId(),
                        completion.getChoreTemplate().getId(),
                        completion.getChoreTemplate().getTitle(),
                        completion.getCompletedByMember() == null ? null : completion.getCompletedByMember().getId(),
                        completion.getCompletedByMember() == null ? "Someone" : completion.getCompletedByMember().getName(),
                        completion.getCompletedAt(),
                        completion.getPeriodStartDate(),
                        completion.getPeriodEndDate(),
                        completion.getChoreTemplate().getPoints()
                ))
                .toList();
    }

    /**
     * Approves and rejects waiting chores in one go, after checking the parent's PIN.
     * Chores no longer waiting (unticked, or decided on another device) are skipped.
     *
     * @throws AccessDeniedException for a wrong or locked PIN (403)
     * @throws BadRequestException   when nothing is chosen or a chore is in both lists (400)
     */
    @Transactional
    public ChoreApprovalResultResponse decide(Family family, ChoreApprovalRequest request) {
        List<UUID> approve = request.approve() == null ? List.of() : request.approve();
        List<UUID> reject = request.reject() == null ? List.of() : request.reject();
        if (approve.isEmpty() && reject.isEmpty()) {
            throw new BadRequestException("Choose at least one chore to approve or reject.");
        }
        Set<UUID> both = new HashSet<>(approve);
        both.retainAll(reject);
        if (!both.isEmpty()) {
            throw new BadRequestException("A chore cannot be approved and rejected at once.");
        }

        FamilyMember parent = parentPinService.verify(family, request.approverMemberId(), request.pin());

        int approved = 0;
        for (UUID id : new HashSet<>(approve)) {
            Optional<ChorePeriodCompletion> found = pending(family, id);
            if (found.isEmpty()) {
                continue;
            }
            ChorePeriodCompletion completion = found.get();
            int points = completion.getChoreTemplate().getPoints() == null
                    ? 0 : completion.getChoreTemplate().getPoints();
            completion.setStatus(ChoreCompletionStatus.APPROVED);
            completion.setPointsAwarded(points);
            chorePeriodCompletionRepository.save(completion);
            choreActivityLog.record(family, completion.getChoreTemplate(), completion.getCompletedByMember(),
                    ChoreActivityKind.APPROVED, points, parent, null, completion.getPeriodStartDate());
            approved++;
        }

        int rejected = 0;
        for (UUID id : new HashSet<>(reject)) {
            Optional<ChorePeriodCompletion> found = pending(family, id);
            if (found.isEmpty()) {
                continue;
            }
            ChorePeriodCompletion completion = found.get();
            chorePeriodCompletionRepository.delete(completion);
            choreActivityLog.record(family, completion.getChoreTemplate(), completion.getCompletedByMember(),
                    ChoreActivityKind.REJECTED, 0, parent, request.reason(), completion.getPeriodStartDate());
            rejected++;
        }

        log.info("Chores decided, familyId={}, parentId={}, approved={}, rejected={}",
                family.getId(), parent.getId(), approved, rejected);
        return new ChoreApprovalResultResponse(approved, rejected);
    }

    /** Every member's points balance, parents and kids, by name. */
    public List<ChorePointsResponse> points(Family family) {
        Map<UUID, Integer> sums = new HashMap<>();
        for (Object[] row : choreActivityRepository.sumPointsByMember(family)) {
            sums.put((UUID) row[0], ((Number) row[1]).intValue());
        }
        return familyMemberRepository.findByFamily(family).stream()
                .sorted(Comparator.comparing((FamilyMember m) -> m.getName().toLowerCase(Locale.ROOT))
                        .thenComparing(m -> m.getId().toString()))
                .map(member -> new ChorePointsResponse(
                        member.getId(), member.getName(), member.getRole(), sums.getOrDefault(member.getId(), 0)))
                .toList();
    }

    /**
     * The chore log, newest first, for everyone or one member. Needs a parent's PIN.
     *
     * @throws AccessDeniedException for a wrong or locked PIN (403)
     */
    public List<ChoreActivityResponse> activity(Family family, ChoreActivityRequest request) {
        parentPinService.verify(family, request.approverMemberId(), request.pin());
        List<ChoreActivity> lines;
        if (request.memberId() == null) {
            lines = choreActivityRepository.findByFamilyOrderByOccurredAtDescIdDesc(family);
        } else {
            FamilyMember member = familyMemberRepository.findById(request.memberId())
                    .orElseThrow(() -> new ResourceNotFoundException("Family Member", request.memberId()));
            if (!member.getFamily().getId().equals(family.getId())) {
                throw new AccessDeniedException("Unauthorized");
            }
            lines = choreActivityRepository.findByFamilyAndMemberOrderByOccurredAtDescIdDesc(family, member);
        }
        return lines.stream().limit(LOG_LIMIT).map(ChoreActivityResponse::toDto).toList();
    }

    private Optional<ChorePeriodCompletion> pending(Family family, UUID completionId) {
        return chorePeriodCompletionRepository.findByIdAndFamily(completionId, family)
                .filter(completion -> completion.getStatus() == ChoreCompletionStatus.PENDING);
    }
}
