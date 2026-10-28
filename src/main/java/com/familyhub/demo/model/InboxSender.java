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

/** An address, beyond the members' own, that the family lets send to its inbox. */
@Entity
@Table(name = "inbox_sender")
@Getter
@Setter
public class InboxSender {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "family_id", nullable = false)
    private Family family;

    /** Lower case. */
    @Column(nullable = false, length = 320)
    private String address;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;
}
