package com.familyhub.demo.repository;

import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.InboxImport;
import com.familyhub.demo.model.InboxMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface InboxImportRepository extends JpaRepository<InboxImport, UUID> {
    List<InboxImport> findByMessage(InboxMessage message);

    List<InboxImport> findTop100ByFamilyOrderByCreatedAtDesc(Family family);
}
