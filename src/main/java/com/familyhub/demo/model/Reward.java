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

import java.time.Instant;
import java.util.UUID;

/** Something in the family's rewards shop, priced in chore points. */
@Entity
@Table(name = "reward")
@Getter
@Setter
public class Reward {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "family_id", nullable = false)
    private Family family;

    @Column(nullable = false, length = 60)
    private String title;

    @Column(length = 200)
    private String description;

    @Column(nullable = false)
    private int points;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    /** Set when a parent removes it from the shop; null while it is on offer. */
    @Column(name = "archived_at")
    private Instant archivedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
