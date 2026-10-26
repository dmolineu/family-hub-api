package com.familyhub.demo.service;

import com.familyhub.demo.repository.FamilyMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

/**
 * Saves PIN attempt bookkeeping in its own transaction. A wrong PIN makes the request
 * fail, which rolls back its transaction; without this the failed try would be
 * forgotten and the lockout would never trigger.
 */
@Service
@RequiredArgsConstructor
public class PinAttemptRecorder {
    private final FamilyMemberRepository familyMemberRepository;

    /**
     * Stores a parent's failed-attempt count and lock.
     *
     * @param memberId    the parent
     * @param attempts    wrong tries since the last success or lock
     * @param lockedUntil when the lock ends, or null when not locked
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(UUID memberId, int attempts, Instant lockedUntil) {
        familyMemberRepository.updatePinAttempts(memberId, attempts, lockedUntil);
    }
}
