package com.familyhub.demo.repository;

import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.RedemptionStatus;
import com.familyhub.demo.model.RewardRedemption;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Members' asks for rewards, always read through the owning family. */
public interface RewardRedemptionRepository extends JpaRepository<RewardRedemption, UUID> {

    /** Asks in one state, oldest first. */
    List<RewardRedemption> findByFamilyAndStatusOrderByRequestedAtAsc(Family family, RedemptionStatus status);

    Optional<RewardRedemption> findByIdAndFamily(UUID id, Family family);

    long countByFamilyAndStatus(Family family, RedemptionStatus status);

    /** The points a member's waiting asks hold. */
    @Query("select coalesce(sum(r.points), 0) from RewardRedemption r "
            + "where r.member = :member and r.status = com.familyhub.demo.model.RedemptionStatus.PENDING")
    int sumPendingPoints(@Param("member") FamilyMember member);

    /** Removes a family's asks (restore replaces the catalogue they point at). */
    @Modifying(flushAutomatically = true)
    @Query("delete from RewardRedemption r where r.family = :family")
    void deleteByFamily(@Param("family") Family family);
}
