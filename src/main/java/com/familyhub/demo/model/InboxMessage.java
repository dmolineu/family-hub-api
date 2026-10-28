package com.familyhub.demo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/** A message the family inbox has handled; its Message-ID is never handled twice. */
@Entity
@Table(name = "inbox_message")
@Getter
@Setter
public class InboxMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "family_id", nullable = false)
    private Family family;

    @Column(name = "message_id", nullable = false, length = 998)
    private String messageId;

    @Column(length = 320)
    private String sender;

    @Column(length = 998)
    private String subject;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private InboxOutcome outcome;

    @Column(length = 1000)
    private String detail;

    /** The Message-ID of the confirmation sent back, so a reply to it finds this message. */
    @Column(name = "reply_message_id", length = 998)
    private String replyMessageId;

    @Column(name = "processed_at", nullable = false)
    private Instant processedAt;
}
