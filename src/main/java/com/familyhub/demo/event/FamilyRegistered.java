package com.familyhub.demo.event;

import java.util.UUID;

/**
 * A family signed up. Published after its row is saved; the dev profile's in-memory
 * Google connects it (Phase 4).
 *
 * @param familyId the new family's id
 */
public record FamilyRegistered(UUID familyId) {
}
