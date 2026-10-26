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

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "chore_period_completion")
@Getter
@Setter
public class ChorePeriodCompletion {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "chore_template_id", nullable = false)
    private ChoreTemplate choreTemplate;

    /** Who gets the credit: the assignee, or whoever picked up an unassigned chore. */
    @ManyToOne
    @JoinColumn(name = "completed_by_member_id")
    private FamilyMember completedByMember;

    @Column(nullable = false)
    private LocalDate periodStartDate;

    @Column(nullable = false)
    private LocalDate periodEndDate;

    @Column(nullable = false)
    private LocalDateTime completedAt;

    /** PENDING while a kid's chore waits for a parent; APPROVED once its points count. */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private ChoreCompletionStatus status = ChoreCompletionStatus.APPROVED;

    /** Points granted on approval; null while pending. */
    @Column(name = "points_awarded")
    private Integer pointsAwarded;
}
