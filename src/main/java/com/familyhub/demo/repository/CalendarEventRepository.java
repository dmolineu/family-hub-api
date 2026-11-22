package com.familyhub.demo.repository;

import org.springframework.data.jpa.repository.Modifying;
import com.familyhub.demo.model.CalendarEvent;
import com.familyhub.demo.model.Family;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDate;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.EventSource;
import com.familyhub.demo.model.GoogleSyncedCalendar;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CalendarEventRepository extends JpaRepository<CalendarEvent, UUID> {
    void deleteByMemberAndSource(FamilyMember member, EventSource source);

    void deleteBySyncedCalendarAndSource(GoogleSyncedCalendar syncedCalendar, EventSource source);

    List<CalendarEvent> findByFamily(Family family);
    Optional<CalendarEvent> findByFamilyAndId(Family family, UUID uuid);

    @Query("SELECT e FROM CalendarEvent e WHERE e.family = :family " +
            "AND e.recurrenceRule IS NULL " +
            "AND e.recurringEvent IS NULL")
    List<CalendarEvent> findRegularEventsByFamily(@Param("family") Family family);

    @Query("SELECT e FROM CalendarEvent e WHERE e.family = :family " +
            "AND e.recurrenceRule IS NOT NULL " +
            "AND e.recurringEvent IS NULL " +
            "AND e.date <= :rangeEnd")
    List<CalendarEvent> findRecurringParentsByFamily(@Param("family") Family family, @Param("rangeEnd") LocalDate rangeEnd);

    @Query("SELECT e FROM CalendarEvent e " +
            "WHERE e.recurringEvent.id IN :parentIds")
    List<CalendarEvent> findExceptionsByParentIds(@Param("parentIds") Collection<UUID> parentIds);

    Optional<CalendarEvent> findByRecurringEventAndOriginalDate(CalendarEvent recurringEvent, LocalDate originalDate);

    /**
     * One calendar's copy of a Google event. A shared event keeps the same Google id in
     * every calendar it appears in, so the id alone does not identify a row.
     */
    Optional<CalendarEvent> findBySyncedCalendarAndGoogleEventId(GoogleSyncedCalendar syncedCalendar,
                                                                 String googleEventId);

    /** Deletes one calendar's copy of a Google event, leaving other calendars' copies. */
    void deleteBySyncedCalendarAndGoogleEventId(GoogleSyncedCalendar syncedCalendar, String googleEventId);

    /** Native events only: Google-synced rows are left for the sync to manage. */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from CalendarEvent e where e.family = :family and e.source = com.familyhub.demo.model.EventSource.NATIVE")
    void deleteNativeByFamily(@Param("family") Family family);

    /**
     * Phase 4: the family's own events not yet copied to Google, changed and cancelled
     * dates of series included.
     *
     * @param familyId the family's id
     * @return the rows waiting to be copied
     */
    @Query("select e from CalendarEvent e where e.family.id = :familyId "
            + "and e.source = com.familyhub.demo.model.EventSource.NATIVE and e.copiedAt is null")
    List<CalendarEvent> findUncopied(@Param("familyId") UUID familyId);

    /**
     * Phase 4: the families with events waiting to be copied to Google.
     *
     * @return their ids
     */
    @Query("select distinct e.family.id from CalendarEvent e "
            + "where e.source = com.familyhub.demo.model.EventSource.NATIVE and e.copiedAt is null")
    List<UUID> findFamiliesWithUncopied();
}
