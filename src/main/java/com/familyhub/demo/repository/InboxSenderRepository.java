package com.familyhub.demo.repository;

import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.InboxSender;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InboxSenderRepository extends JpaRepository<InboxSender, UUID> {
    List<InboxSender> findByFamilyOrderByAddress(Family family);

    Optional<InboxSender> findByFamilyAndAddress(Family family, String address);

    Optional<InboxSender> findByIdAndFamily(UUID id, Family family);
}
