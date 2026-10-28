package com.familyhub.demo.service;

import com.familyhub.demo.dto.ParentPinRequest;
import com.familyhub.demo.dto.RedemptionAskRequest;
import com.familyhub.demo.dto.RedemptionDecisionRequest;
import com.familyhub.demo.dto.RedemptionDecisionResultResponse;
import com.familyhub.demo.dto.RedemptionResponse;
import com.familyhub.demo.dto.RewardBackup;
import com.familyhub.demo.dto.RewardRequest;
import com.familyhub.demo.dto.RewardResponse;
import com.familyhub.demo.dto.RewardShopResponse;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.exception.ConflictException;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.model.ChoreActivityKind;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.RedemptionStatus;
import com.familyhub.demo.model.Reward;
import com.familyhub.demo.model.RewardRedemption;
import com.familyhub.demo.repository.ChoreActivityRepository;
import com.familyhub.demo.repository.FamilyMemberRepository;
import com.familyhub.demo.repository.RewardRedemptionRepository;
import com.familyhub.demo.repository.RewardRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The rewards shop. Parents keep the catalogue with their own PIN; anyone may ask for a
 * reward they can afford, which holds its points; a parent's PIN approves (the points
 * are spent, as a REDEEMED line in the points log) or declines (a DECLINED line, the
 * points freed). A member's balance stays the sum of their lines in that log.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class RewardService {
    private final RewardRepository rewardRepository;
    private final RewardRedemptionRepository redemptionRepository;
    private final FamilyMemberRepository familyMemberRepository;
    private final ChoreActivityRepository choreActivityRepository;
    private final ChoreActivityLog choreActivityLog;
    private final ParentPinService parentPinService;
    private final Clock clock;

    /** What is on offer and which asks are waiting; needs no PIN. */
    public RewardShopResponse shop(Family family) {
        return new RewardShopResponse(
                rewardRepository.findByFamilyAndArchivedAtIsNullOrderByPointsAscTitleAsc(family).stream()
                        .map(RewardResponse::toDto)
                        .toList(),
                redemptionRepository.findByFamilyAndStatusOrderByRequestedAtAsc(family, RedemptionStatus.PENDING)
                        .stream()
                        .map(RedemptionResponse::toDto)
                        .toList()
        );
    }

    /**
     * Opens the catalogue for changing, after checking a parent's PIN, so a wrong PIN
     * shows before anyone types out a reward.
     *
     * @throws AccessDeniedException for a kid, a wrong PIN or a locked PIN (403)
     */
    public RewardShopResponse manage(Family family, ParentPinRequest request) {
        parentPinService.verify(family, request.approverMemberId(), request.pin());
        return shop(family);
    }

    /** How many asks wait for a parent, for the approvals badge. */
    public long countWaiting(Family family) {
        return redemptionRepository.countByFamilyAndStatus(family, RedemptionStatus.PENDING);
    }

    // ---------------------------------------------------------------- catalogue

    /**
     * Adds a reward to the shop.
     *
     * @throws AccessDeniedException for a kid, a wrong PIN or a locked PIN (403)
     */
    @Transactional
    public RewardResponse create(Family family, RewardRequest request) {
        parentPinService.verify(family, request.approverMemberId(), request.pin());
        Reward reward = new Reward();
        reward.setFamily(family);
        apply(reward, request);
        Reward saved = rewardRepository.save(reward);
        log.info("Reward created, rewardId={}, familyId={}", saved.getId(), family.getId());
        return RewardResponse.toDto(saved);
    }

    /**
     * Changes a reward's name, description or price. Asks already made keep the price
     * they were made at.
     *
     * @throws AccessDeniedException for a kid, a wrong PIN or a locked PIN (403)
     * @throws ResourceNotFoundException for a reward not in this family's shop (404)
     */
    @Transactional
    public RewardResponse update(Family family, UUID rewardId, RewardRequest request) {
        Reward reward = onOffer(family, rewardId);
        parentPinService.verify(family, request.approverMemberId(), request.pin());
        apply(reward, request);
        return RewardResponse.toDto(rewardRepository.save(reward));
    }

    /**
     * Takes a reward out of the shop. Asks already waiting for it can still be decided.
     *
     * @throws AccessDeniedException for a kid, a wrong PIN or a locked PIN (403)
     */
    @Transactional
    public void archive(Family family, UUID rewardId, ParentPinRequest request) {
        Reward reward = onOffer(family, rewardId);
        parentPinService.verify(family, request.approverMemberId(), request.pin());
        reward.setArchivedAt(Instant.now(clock));
        rewardRepository.save(reward);
    }

    // ------------------------------------------------------------------ asking

    /**
     * A member asks for a reward. Its points are held until a parent decides, so the
     * same points cannot be asked for twice.
     *
     * @throws ConflictException when the member's points, less those already held, do not cover it (409)
     */
    @Transactional
    public RedemptionResponse ask(Family family, UUID rewardId, RedemptionAskRequest request) {
        Reward reward = onOffer(family, rewardId);
        FamilyMember member = member(family, request.memberId());
        int available = choreActivityRepository.sumPoints(member) - redemptionRepository.sumPendingPoints(member);
        if (available < reward.getPoints()) {
            throw new ConflictException("%s has %d points left; %s costs %d.".formatted(
                    member.getName(), Math.max(available, 0), reward.getTitle(), reward.getPoints()));
        }
        RewardRedemption redemption = new RewardRedemption();
        redemption.setFamily(family);
        redemption.setReward(reward);
        redemption.setRewardTitle(reward.getTitle());
        redemption.setPoints(reward.getPoints());
        redemption.setMember(member);
        redemption.setStatus(RedemptionStatus.PENDING);
        redemption.setRequestedAt(Instant.now(clock));
        return RedemptionResponse.toDto(redemptionRepository.save(redemption));
    }

    /**
     * The member who asked takes their waiting ask back; nothing is logged.
     *
     * @throws AccessDeniedException when someone else tries (403)
     */
    @Transactional
    public void cancel(Family family, UUID redemptionId, RedemptionAskRequest request) {
        RewardRedemption redemption = redemptionRepository.findByIdAndFamily(redemptionId, family)
                .filter(r -> r.getStatus() == RedemptionStatus.PENDING)
                .orElseThrow(() -> new ResourceNotFoundException("Reward request", redemptionId));
        if (!redemption.getMember().getId().equals(request.memberId())) {
            throw new AccessDeniedException("Only the person who asked can take it back.");
        }
        redemption.setStatus(RedemptionStatus.CANCELLED);
        redemption.setDecidedAt(Instant.now(clock));
        redemptionRepository.save(redemption);
    }

    // ---------------------------------------------------------------- deciding

    /**
     * Approves and declines waiting asks in one go, after checking the parent's PIN.
     * Asks no longer waiting (taken back, or decided on another device) are skipped.
     * All or nothing: if any approval is no longer covered (points taken back since the
     * ask), nothing is decided.
     *
     * @throws AccessDeniedException for a kid, a wrong PIN or a locked PIN (403)
     * @throws BadRequestException   when nothing is chosen or an ask is in both lists (400)
     * @throws ConflictException     when an approval is no longer covered by the member's points (409)
     */
    @Transactional
    public RedemptionDecisionResultResponse decide(Family family, RedemptionDecisionRequest request) {
        Set<UUID> approve = new LinkedHashSet<>(request.approve() == null ? List.of() : request.approve());
        Set<UUID> decline = new LinkedHashSet<>(request.decline() == null ? List.of() : request.decline());
        if (approve.isEmpty() && decline.isEmpty()) {
            throw new BadRequestException("Choose at least one reward to approve or decline.");
        }
        Set<UUID> both = new HashSet<>(approve);
        both.retainAll(decline);
        if (!both.isEmpty()) {
            throw new BadRequestException("A reward cannot be approved and declined at once.");
        }

        FamilyMember parent = parentPinService.verify(family, request.approverMemberId(), request.pin());
        Instant now = Instant.now(clock);

        int approved = 0;
        for (UUID id : approve) {
            Optional<RewardRedemption> found = waiting(family, id);
            if (found.isEmpty()) {
                continue;
            }
            RewardRedemption redemption = found.get();
            FamilyMember member = redemption.getMember();
            int balance = choreActivityRepository.sumPoints(member);
            if (balance < redemption.getPoints()) {
                throw new ConflictException("%s has %d points now; %s costs %d.".formatted(
                        member.getName(), Math.max(balance, 0), redemption.getRewardTitle(), redemption.getPoints()));
            }
            redemption.setStatus(RedemptionStatus.APPROVED);
            decided(redemption, parent, now, null);
            choreActivityLog.recordReward(family, redemption.getRewardTitle(), member,
                    ChoreActivityKind.REDEEMED, -redemption.getPoints(), parent, null);
            // Flushed so the next approval for the same member sees this spend.
            choreActivityRepository.flush();
            approved++;
        }

        int declined = 0;
        for (UUID id : decline) {
            Optional<RewardRedemption> found = waiting(family, id);
            if (found.isEmpty()) {
                continue;
            }
            RewardRedemption redemption = found.get();
            redemption.setStatus(RedemptionStatus.DECLINED);
            decided(redemption, parent, now, request.reason());
            choreActivityLog.recordReward(family, redemption.getRewardTitle(), redemption.getMember(),
                    ChoreActivityKind.DECLINED, 0, parent, request.reason());
            declined++;
        }

        log.info("Rewards decided, familyId={}, parentId={}, approved={}, declined={}",
                family.getId(), parent.getId(), approved, declined);
        return new RedemptionDecisionResultResponse(approved, declined);
    }

    // ------------------------------------------------------------------ backup

    /** The rewards on offer, for the backup file. */
    public List<RewardBackup> exportAll(Family family) {
        return rewardRepository.findByFamilyAndArchivedAtIsNullOrderByPointsAscTitleAsc(family).stream()
                .map(r -> new RewardBackup(r.getTitle(), r.getDescription(), r.getPoints()))
                .toList();
    }

    /**
     * Replaces the shop with a backup's. Waiting asks are dropped (their points were only
     * held); spent and declined ones stay in the restored points log.
     *
     * @throws BadRequestException for a reward the API itself would refuse
     */
    @Transactional
    public int replaceAll(List<RewardBackup> backups, Family family) {
        redemptionRepository.deleteByFamily(family);
        rewardRepository.deleteByFamily(family);
        if (backups == null || backups.isEmpty()) {
            return 0;
        }
        for (RewardBackup backup : backups) {
            String title = backup.title() == null ? "" : backup.title().trim();
            if (title.isEmpty() || title.length() > 60 || backup.points() < 1 || backup.points() > 10_000
                    || (backup.description() != null && backup.description().length() > 200)) {
                throw new BadRequestException("The backup holds a reward the shop cannot take: \"" + title + "\"");
            }
            Reward reward = new Reward();
            reward.setFamily(family);
            reward.setTitle(title);
            reward.setDescription(blankToNull(backup.description()));
            reward.setPoints(backup.points());
            rewardRepository.save(reward);
        }
        return backups.size();
    }

    // ----------------------------------------------------------------- helpers

    private Reward onOffer(Family family, UUID rewardId) {
        return rewardRepository.findByIdAndFamily(rewardId, family)
                .filter(r -> r.getArchivedAt() == null)
                .orElseThrow(() -> new ResourceNotFoundException("Reward", rewardId));
    }

    private FamilyMember member(Family family, UUID memberId) {
        return familyMemberRepository.findById(memberId)
                .filter(m -> m.getFamily().getId().equals(family.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Family Member", memberId));
    }

    private Optional<RewardRedemption> waiting(Family family, UUID id) {
        return redemptionRepository.findByIdAndFamily(id, family)
                .filter(r -> r.getStatus() == RedemptionStatus.PENDING);
    }

    private void decided(RewardRedemption redemption, FamilyMember parent, Instant now, String reason) {
        redemption.setDecidedAt(now);
        redemption.setDecidedBy(parent);
        redemption.setReason(blankToNull(reason));
        redemptionRepository.save(redemption);
    }

    private static void apply(Reward reward, RewardRequest request) {
        reward.setTitle(request.title().trim());
        reward.setDescription(blankToNull(request.description()));
        reward.setPoints(request.points());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
