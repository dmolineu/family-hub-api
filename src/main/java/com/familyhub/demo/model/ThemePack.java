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
 * A look the family imported: one validated JSON document holding colour tokens and
 * embedded images (see {@code ThemePackDocumentValidator} for the accepted shape).
 * The document is stored verbatim so an export returns exactly what was imported.
 */
@Entity
@Table(name = "theme_pack")
@Getter
@Setter
public class ThemePack {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "family_id", nullable = false)
    private Family family;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(nullable = false)
    private boolean dark;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String document;

    /** Derived card colours + thumbnail as JSON; null until computed. */
    @Column(columnDefinition = "TEXT")
    private String preview;

    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
