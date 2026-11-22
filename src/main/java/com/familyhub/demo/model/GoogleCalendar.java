package com.familyhub.demo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * A Google calendar a family can see, cached once however many of its accounts see it
 * (Phase 4). {@link #pollingToken} is the account whose sync token this calendar is
 * polled with; {@link #laneMembers} are whose lanes an event goes in when it names no
 * members itself. Where new events are written is the family's default calendar, not
 * this.
 */
@Entity
@Getter
@Setter
@Table(name = "google_calendar")
public class GoogleCalendar {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "family_id", nullable = false)
    private Family family;

    @Column(name = "google_calendar_id", nullable = false, length = 1024)
    private String googleCalendarId;

    @Column(columnDefinition = "TEXT")
    private String summary;

    @Column(length = 16)
    private String color;

    @Column(name = "time_zone", length = 100)
    private String timeZone;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "sync_token_id")
    private GoogleOAuthToken pollingToken;

    @Column(name = "sync_token", columnDefinition = "TEXT")
    private String syncToken;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "last_sync_error", columnDefinition = "TEXT")
    private String lastSyncError;

    @Column(name = "full_sync_required", nullable = false)
    private boolean fullSyncRequired = true;

    @Column(nullable = false)
    private boolean enabled = true;

    @ManyToMany
    @JoinTable(name = "google_calendar_member",
            joinColumns = @JoinColumn(name = "calendar_id"),
            inverseJoinColumns = @JoinColumn(name = "member_id"))
    private Set<FamilyMember> laneMembers = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
