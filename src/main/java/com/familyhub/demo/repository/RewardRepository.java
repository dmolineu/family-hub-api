package com.familyhub.demo.repository;

import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.Reward;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The rewards shop's catalogue, always read through the owning family. */
public interface RewardRepository extends JpaRepository<Reward, UUID> {

    /** What is on offer, cheapest first. */
    List<Reward> findByFamilyAndArchivedAtIsNullOrderByPointsAscTitleAsc(Family family);

    Optional<Reward> findByIdAndFamily(UUID id, Family family);

    /** Removes a family's whole catalogue (restore replaces it). */
    @Modifying(flushAutomatically = true)
    @Query("delete from Reward r where r.family = :family")
    void deleteByFamily(@Param("family") Family family);
}
