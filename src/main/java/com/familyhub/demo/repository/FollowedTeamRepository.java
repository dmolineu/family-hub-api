package com.familyhub.demo.repository;

import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FollowedTeam;
import com.familyhub.demo.sports.SportsLeague;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Followed teams, always read through the owning family except by the background sync. */
public interface FollowedTeamRepository extends JpaRepository<FollowedTeam, UUID> {

    List<FollowedTeam> findByFamilyOrderByCreatedAtAsc(Family family);

    Optional<FollowedTeam> findByIdAndFamily(UUID id, Family family);

    boolean existsByFamilyAndLeagueAndProviderTeamId(Family family, SportsLeague league, String providerTeamId);

    /** Teams whose schedule has never been read (just followed, restored, or the source was down). */
    List<FollowedTeam> findByLastSyncedAtIsNull();

    /** Removes a family's teams (restore replaces them). */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("delete from FollowedTeam t where t.family = :family")
    void deleteByFamily(@Param("family") Family family);
}
