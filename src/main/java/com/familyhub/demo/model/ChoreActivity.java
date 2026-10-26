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
import java.time.LocalDate;
import java.util.UUID;

/**
 * One line of the append-only chore log. A member's points balance is the sum of the
 * points on their lines. Names are copied in so the log still reads correctly after a
 * member or chore is renamed or deleted (the references then become null).
 */
@Entity
@Table(name = "chore_activity")
@Getter
@Setter
public class ChoreActivity {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "family_id", nullable = false)
    private Family family;

    @ManyToOne
    @JoinColumn(name = "chore_template_id")
    private ChoreTemplate choreTemplate;

    @Column(name = "chore_title", nullable = false, length = 100)
    private String choreTitle;

    /** The member the chore is credited to. */
    @ManyToOne
    @JoinColumn(name = "member_id")
    private FamilyMember member;

    @Column(name = "member_name", nullable = false)
    private String memberName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 12)
    private ChoreActivityKind kind;

    @Column(nullable = false)
    private int points;

    /** The parent who approved, rejected or reversed; null for a plain tick or untick. */
    @ManyToOne
    @JoinColumn(name = "actor_member_id")
    private FamilyMember actor;

    @Column(name = "actor_name")
    private String actorName;

    @Column(length = 200)
    private String reason;

    @Column(name = "period_start_date")
    private LocalDate periodStartDate;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;
}
