package com.familyhub.demo.model;

import com.familyhub.demo.sports.SportsLeague;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/** A team the family follows: its games go on the calendar, in its fans' lanes. */
@Entity
@Table(name = "followed_team")
@Getter
@Setter
public class FollowedTeam {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "family_id", nullable = false)
    private Family family;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SportsLeague league;

    @Column(name = "provider_team_id", nullable = false, length = 40)
    private String providerTeamId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "short_name", length = 60)
    private String shortName;

    @Column(length = 10)
    private String abbreviation;

    @Column(name = "logo_url", length = 500)
    private String logoUrl;

    @Column(length = 7)
    private String color;

    @Column(name = "show_on_calendar", nullable = false)
    private boolean showOnCalendar = true;

    @Column(name = "hide_scores", nullable = false)
    private boolean hideScores;

    @Column(name = "last_synced_at")
    private Instant lastSyncedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @ManyToMany
    @JoinTable(name = "followed_team_member",
            joinColumns = @JoinColumn(name = "followed_team_id"),
            inverseJoinColumns = @JoinColumn(name = "member_id"))
    private Set<FamilyMember> fans = new HashSet<>();

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
