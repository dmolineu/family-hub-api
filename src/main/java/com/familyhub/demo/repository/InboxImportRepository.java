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

    List<InboxImport> findTop100ByFamilyOrderByCreatedAtDesc(Family family);

    Optional<InboxImport> findByIdAndFamily(UUID id, Family family);

    long countByFamilyAndStatus(Family family, InboxImportStatus status);
}
