package com.familyhub.demo.service;

import com.familyhub.demo.dto.FamilyMemberResponse;
import com.familyhub.demo.dto.MemberPinRequest;
import com.familyhub.demo.dto.MemberRoleRequest;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.MemberRole;
import com.familyhub.demo.repository.FamilyMemberRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * Parents and kids, and each parent's own PIN.
 *
 * <p>Entering a PIN always starts by naming the parent, so five wrong tries lock only
 * that parent's PIN, for 15 minutes. Roles and PINs change only with the family
 * password, because the family shares one login and anyone at the wall could otherwise
 * set a PIN or make themselves a parent.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ParentPinService {
    static final int MAX_ATTEMPTS = 5;
    static final Duration LOCK_DURATION = Duration.ofMinutes(15);

    private final FamilyMemberRepository familyMemberRepository;
    private final PinAttemptRecorder pinAttemptRecorder;
    private final PasswordEncoder passwordEncoder;
    private final Clock clock;

    /**
     * Makes a member a parent or a kid. A kid's PIN is removed, and the last parent
     * cannot become a kid.
     *
     * @throws AccessDeniedException when the family password is wrong (403)
     * @throws BadRequestException   when the change would leave no parent (400)
     */
    @Transactional
    public FamilyMemberResponse setRole(Family family, UUID memberId, MemberRoleRequest request) {
        requireFamilyPassword(family, request.password());
        FamilyMember member = requireMember(family, memberId);
        if (member.getRole() == request.role()) {
            return FamilyMemberResponse.toDto(member);
        }
        if (request.role() == MemberRole.KID
                && familyMemberRepository.countByFamilyAndRole(family, MemberRole.PARENT) <= 1) {
            throw new BadRequestException("A family needs at least one parent to approve chores.");
        }
        member.setRole(request.role());
        if (request.role() == MemberRole.KID) {
            member.setPinHash(null);
            member.setPinFailedAttempts(0);
            member.setPinLockedUntil(null);
        }
        log.info("Member role changed, memberId={}, familyId={}, role={}", memberId, family.getId(), request.role());
        return FamilyMemberResponse.toDto(familyMemberRepository.save(member));
    }

    /**
     * Sets or replaces a parent's PIN and clears any lock on it.
     *
     * @throws AccessDeniedException when the family password is wrong (403)
     * @throws BadRequestException   when the member is a kid (400)
     */
    @Transactional
    public void setPin(Family family, UUID memberId, MemberPinRequest request) {
        requireFamilyPassword(family, request.password());
        FamilyMember member = requireMember(family, memberId);
        if (member.getRole() != MemberRole.PARENT) {
            throw new BadRequestException("Only parents have a PIN. Make " + member.getName() + " a parent first.");
        }
        member.setPinHash(passwordEncoder.encode(request.pin()));
        member.setPinFailedAttempts(0);
        member.setPinLockedUntil(null);
        familyMemberRepository.save(member);
        log.info("Parent PIN set, memberId={}, familyId={}", memberId, family.getId());
    }

    /**
     * Checks a parent's PIN and returns that parent. Wrong tries are counted even though
     * the request fails; the fifth locks this parent's PIN for 15 minutes.
     *
     * @throws AccessDeniedException for a kid, a wrong PIN or a locked PIN (403)
     * @throws BadRequestException   when the parent has not set a PIN yet (400)
     */
    public FamilyMember verify(Family family, UUID parentMemberId, String pin) {
        FamilyMember parent = requireMember(family, parentMemberId);
        if (parent.getRole() != MemberRole.PARENT) {
            throw new AccessDeniedException("Only a parent can do this.");
        }
        if (!parent.hasPin()) {
            throw new BadRequestException(parent.getName() + " hasn't set a PIN yet. Set one on their profile.");
        }
        Instant now = Instant.now(clock);
        if (parent.getPinLockedUntil() != null && parent.getPinLockedUntil().isAfter(now)) {
            long minutes = Math.max(1, (Duration.between(now, parent.getPinLockedUntil()).toSeconds() + 59) / 60);
            throw new AccessDeniedException(parent.getName() + "'s PIN is locked after too many wrong tries. Try again in "
                    + minutes + (minutes == 1 ? " minute." : " minutes."));
        }
        if (pin == null || !passwordEncoder.matches(pin, parent.getPinHash())) {
            int attempts = parent.getPinFailedAttempts() + 1;
            if (attempts >= MAX_ATTEMPTS) {
                pinAttemptRecorder.record(parent.getId(), 0, now.plus(LOCK_DURATION));
                log.warn("Parent PIN locked, memberId={}, familyId={}", parent.getId(), family.getId());
                throw new AccessDeniedException("Wrong PIN. " + parent.getName()
                        + "'s PIN is now locked for 15 minutes.");
            }
            pinAttemptRecorder.record(parent.getId(), attempts, null);
            int left = MAX_ATTEMPTS - attempts;
            throw new AccessDeniedException("Wrong PIN for " + parent.getName() + ". " + left
                    + (left == 1 ? " try left." : " tries left."));
        }
        if (parent.getPinFailedAttempts() > 0 || parent.getPinLockedUntil() != null) {
            pinAttemptRecorder.record(parent.getId(), 0, null);
        }
        return parent;
    }

    private void requireFamilyPassword(Family family, String password) {
        if (password == null || !passwordEncoder.matches(password, family.getPasswordHash())) {
            throw new AccessDeniedException("That isn't the password this family signs in with.");
        }
    }

    private FamilyMember requireMember(Family family, UUID memberId) {
        FamilyMember member = familyMemberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Family Member", memberId));
        if (!member.getFamily().getId().equals(family.getId())) {
            throw new AccessDeniedException("Unauthorized");
        }
        return member;
    }
}
