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

/**
 * One thing a message added, or proposed: a photo, an event or a recipe. Undoing a
 * message takes off everything it added; an event waiting for review keeps the
 * reader's proposal in {@link #details}.
 */
@Entity
@Table(name = "inbox_import")
@Getter
@Setter
public class InboxImport {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "family_id", nullable = false)
    private Family family;

    @ManyToOne
    @JoinColumn(name = "message_id", nullable = false)
    private InboxMessage message;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private InboxImportKind kind;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private InboxImportStatus status;

    @Column(nullable = false, length = 200)
    private String title;

    /** The reader's proposal as JSON, for an import waiting for review. */
    @Column(columnDefinition = "TEXT")
    private String details;

    @Column(length = 10)
    private String confidence;

    @Column(name = "calendar_event_id")
    private UUID calendarEventId;

    @Column(name = "photo_id")
    private UUID photoId;

    @Column(name = "recipe_id")
    private UUID recipeId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "decided_by")
    private UUID decidedBy;
}
