package com.familyhub.demo.repository;

import com.familyhub.demo.model.GoogleCalendar;
import com.familyhub.demo.model.GoogleCalendarAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The Google calendars families can see (Phase 4 cache). */
public interface GoogleCalendarRepository extends JpaRepository<GoogleCalendar, UUID> {

    /**
     * Every account's access to a calendar.
     *
     * @param calendar the cached calendar
     * @return one row per signed-in account that sees it
     */
    @Query("select a from GoogleCalendarAccess a join fetch a.token where a.calendar = :calendar")
    List<GoogleCalendarAccess> findAccess(@Param("calendar") GoogleCalendar calendar);

    /**
     * A calendar with what a sync needs loaded: its family, the account that polls it and
     * its lane members.
     *
     * @param id the cached calendar's id
     * @return the calendar, if it exists
     */
    @Query("select c from GoogleCalendar c join fetch c.family left join fetch c.pollingToken "
            + "left join fetch c.laneMembers where c.id = :id")
    Optional<GoogleCalendar> findForSync(@Param("id") UUID id);

    /**
     * The enabled calendars of every family, which the scheduler polls in turn.
     *
     * @return their ids
     */
    @Query("select c.id from GoogleCalendar c where c.enabled = true order by c.createdAt")
    List<UUID> findEnabledIds();

    /**
     * A family's enabled calendars, which the calendar shows.
     *
     * @param familyId the family's id
     * @return its enabled calendars
     */
    @Query("select c from GoogleCalendar c where c.family.id = :familyId and c.enabled = true order by c.createdAt")
    List<GoogleCalendar> findEnabledByFamily(@Param("familyId") UUID familyId);

    /**
     * Every calendar a family's accounts can see, switched on or not.
     *
     * @param familyId the family's id
     * @return its calendars, oldest first
     */
    @Query("select c from GoogleCalendar c where c.family.id = :familyId order by c.createdAt")
    List<GoogleCalendar> findAllByFamilyId(@Param("familyId") UUID familyId);

    /**
     * The calendars one account polls.
     *
     * @param tokenId the signed-in account
     * @return its calendars
     */
    @Query("select c from GoogleCalendar c where c.pollingToken.id = :tokenId")
    List<GoogleCalendar> findPolledBy(@Param("tokenId") UUID tokenId);
}
