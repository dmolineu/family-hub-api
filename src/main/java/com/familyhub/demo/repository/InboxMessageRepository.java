package com.familyhub.demo.repository;

import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.InboxMessage;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface InboxMessageRepository extends JpaRepository<InboxMessage, UUID> {
    Optional<InboxMessage> findByFamilyAndMessageId(Family family, String messageId);

    /** The message whose confirmation has this Message-ID. */
    Optional<InboxMessage> findByFamilyAndReplyMessageId(Family family, String replyMessageId);
}
