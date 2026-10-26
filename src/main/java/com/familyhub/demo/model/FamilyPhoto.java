package com.familyhub.demo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
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
 * A photo uploaded for the screensaver, with its thumbnail. Listing never loads
 * the image bytes (see the repository's summary query); only content requests do.
 */
@Entity
@Table(name = "family_photo")
@Getter
@Setter
public class FamilyPhoto {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "family_id", nullable = false)
    private Family family;

    @Column(name = "content_type", nullable = false, length = 20)
    private String contentType;

    @Column(nullable = false)
    private int width;

    @Column(nullable = false)
    private int height;

    @Column(name = "size_bytes", nullable = false)
    private int sizeBytes;

    // An explicit length keeps the dev profile's H2 schema from defaulting to
    // varbinary(255); on PostgreSQL any length maps to bytea.
    @Column(nullable = false, length = 16 * 1024 * 1024)
    private byte[] data;

    @Column(nullable = false, length = 1024 * 1024)
    private byte[] thumbnail;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
