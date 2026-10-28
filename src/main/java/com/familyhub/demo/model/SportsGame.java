package com.familyhub.demo.model;

import com.familyhub.demo.sports.GameState;
import com.familyhub.demo.sports.SportsLeague;
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

/** A followed team's game as last read from its provider; scores refresh while it is live. */
@Entity
@Table(name = "sports_game")
@Getter
@Setter
public class SportsGame {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "family_id", nullable = false)
    private Family family;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private SportsLeague league;

    @Column(name = "provider_game_id", nullable = false, length = 40)
    private String providerGameId;

    @Column(name = "start_at", nullable = false)
    private Instant startAt;

    @Column(name = "time_tbd", nullable = false)
    private boolean timeTbd;

    @Column(name = "home_team_id", length = 40)
    private String homeTeamId;

    @Column(name = "home_name", length = 100)
    private String homeName;

    @Column(name = "home_abbreviation", length = 10)
    private String homeAbbreviation;

    @Column(name = "home_score")
    private Integer homeScore;

    @Column(name = "away_team_id", length = 40)
    private String awayTeamId;

    @Column(name = "away_name", length = 100)
    private String awayName;

    @Column(name = "away_abbreviation", length = 10)
    private String awayAbbreviation;

    @Column(name = "away_score")
    private Integer awayScore;

    @Column(length = 150)
    private String venue;

    /** TV channels, one per line. */
    @Column(length = 300)
    private String broadcasts;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private GameState state;

    @Column(length = 80)
    private String detail;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;
}
