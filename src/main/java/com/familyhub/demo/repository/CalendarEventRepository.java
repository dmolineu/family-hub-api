package com.familyhub.demo.repository;

import com.familyhub.demo.model.CalendarEvent;
import com.familyhub.demo.model.Family;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * The hub's own pre-Phase 4 events, which wait to be copied to Google (see
 * {@link CalendarEvent}). Only the copy and the inbox read them now.
 */
public interface CalendarEventRepository extends JpaRepository<CalendarEvent, UUID> {
    List<CalendarEvent> findByFamily(Family family);

    Optional<CalendarEvent> findByFamilyAndId(Family family, UUID uuid);

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
