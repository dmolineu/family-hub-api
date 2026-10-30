package com.familyhub.demo.repository;

import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.MemberRole;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface FamilyMemberRepository extends JpaRepository<FamilyMember, UUID> {
    /**
     * A family's members in their listed order. Without the ORDER BY, Postgres returns
     * them in storage order, which an edit can change. The id only breaks a tie.
     */
    @Query("select m from FamilyMember m where m.family = :family order by m.sortOrder, m.id")
    List<FamilyMember> findByFamily(@Param("family") Family family);

    /** The sort order a new member of the family gets: after everyone already listed. */
    @Query("select coalesce(max(m.sortOrder) + 1, 0) from FamilyMember m where m.family = :family")
    int nextSortOrder(@Param("family") Family family);

    /** How many members of a family hold a role (a family must keep at least one parent). */
    long countByFamilyAndRole(Family family, MemberRole role);

    /**
     * Writes a parent's PIN attempt counter and lock directly, so a wrong PIN is
     * remembered even though the request that carried it fails and rolls back.
     */
    @Modifying
    @Query("update FamilyMember m set m.pinFailedAttempts = :attempts, m.pinLockedUntil = :lockedUntil "
            + "where m.id = :id")
    int updatePinAttempts(@Param("id") UUID id,
                          @Param("attempts") int attempts,
                          @Param("lockedUntil") Instant lockedUntil);
}
