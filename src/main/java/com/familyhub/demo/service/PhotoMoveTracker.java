package com.familyhub.demo.service;

import com.familyhub.demo.dto.PhotoStorageResponse.MoveResult;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Which families' photo libraries are moving right now, and how each family's last
 * move ended. In memory: one server, and a move interrupted by a restart leaves the
 * photos in the old folder, which is still the family's folder.
 */
@Component
public class PhotoMoveTracker {
    private final Set<UUID> moving = ConcurrentHashMap.newKeySet();
    private final Map<UUID, MoveResult> results = new ConcurrentHashMap<>();

    /** Marks a move as started; false if one is already running for the family. */
    public boolean start(UUID familyId) {
        return moving.add(familyId);
    }

    public boolean isMoving(UUID familyId) {
        return moving.contains(familyId);
    }

    /** Records how a move ended and marks it finished. */
    public void finish(UUID familyId, MoveResult result) {
        results.put(familyId, result);
        moving.remove(familyId);
    }

    public Optional<MoveResult> lastResult(UUID familyId) {
        return Optional.ofNullable(results.get(familyId));
    }
}
