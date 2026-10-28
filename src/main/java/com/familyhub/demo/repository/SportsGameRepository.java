package com.familyhub.demo.repository;

import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.SportsGame;
import com.familyhub.demo.sports.GameState;
import com.familyhub.demo.sports.SportsLeague;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Cached games of followed teams. */
public interface SportsGameRepository extends JpaRepository<SportsGame, UUID> {

    Optional<SportsGame> findByFamilyAndLeagueAndProviderGameId(Family family, SportsLeague league, String providerGameId);

    Optional<SportsGame> findByIdAndFamily(UUID id, Family family);

    /** A family's games starting in [from, to), earliest first. */
    List<SportsGame> findByFamilyAndStartAtGreaterThanEqualAndStartAtLessThanOrderByStartAtAsc(
            Family family, Instant from, Instant to);

    /** Games, in any family, in one of these states and starting in [from, to). */
    List<SportsGame> findByStateInAndStartAtGreaterThanEqualAndStartAtLessThan(
            Collection<GameState> states, Instant from, Instant to);

    /** Every family's copy of one game, to apply a refresh to all of them. */
    List<SportsGame> findByLeagueAndProviderGameId(SportsLeague league, String providerGameId);

    List<SportsGame> findByFamilyAndLeague(Family family, SportsLeague league);

    /** Removes a family's games (restore replaces its teams; games come back with the sync). */
    @Modifying(flushAutomatically = true)
    @Query("delete from SportsGame g where g.family = :family")
    void deleteByFamily(@Param("family") Family family);
}
