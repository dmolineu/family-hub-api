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

/** A picture found in the family's source folder; the file itself is only ever read. */
@Entity
@Table(name = "folder_photo")
@Getter
@Setter
public class FolderPhoto {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "family_id", nullable = false)
    private Family family;

    /** The picture's path inside the source folder. */
    @Column(name = "relative_path", nullable = false, length = 1000)
    private String relativePath;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "modified_at", nullable = false)
    private Instant modifiedAt;
}
