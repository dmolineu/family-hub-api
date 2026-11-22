package com.familyhub.demo.repository;

import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.InboxImport;
import com.familyhub.demo.model.InboxImportStatus;
import com.familyhub.demo.model.InboxMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InboxImportRepository extends JpaRepository<InboxImport, UUID> {
    List<InboxImport> findByMessage(InboxMessage message);

    /**
     * Phase 4: the inbox records pointing at some of the family's own events, to point
     * them at the events' Google copies.
     *
     * @param calendarEventIds the events' ids
     * @return the records
     */
    List<InboxImport> findByCalendarEventIdIn(java.util.Collection<UUID> calendarEventIds);

    List<InboxImport> findTop100ByFamilyOrderByCreatedAtDesc(Family family);

    Optional<InboxImport> findByIdAndFamily(UUID id, Family family);

    long countByFamilyAndStatus(Family family, InboxImportStatus status);
}
