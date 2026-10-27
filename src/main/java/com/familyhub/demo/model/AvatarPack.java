package com.familyhub.demo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * A set of characters the family imported: one validated JSON document (see
 * {@code AvatarPackDocumentValidator}), stored verbatim so the picker and an export get
 * exactly what was imported. The count and groups are kept beside it so the pack can be
 * listed without reading its pictures.
 */
@Entity
@Table(name = "avatar_pack")
@Getter
@Setter
public class AvatarPack {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "family_id", nullable = false)
    private Family family;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "character_count", nullable = false)
    private int characterCount;

    /** The pack's groups in order, one per line. */
    @Column(nullable = false, columnDefinition = "TEXT")
    private String groups;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String document;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
