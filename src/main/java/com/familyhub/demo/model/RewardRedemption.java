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
import java.util.UUID;

/**
 * A member's ask for a reward. The reward's title and price are copied in when asked,
 * so editing the reward later never changes what was asked for.
 */
@Entity
@Table(name = "reward_redemption")
@Getter
@Setter
public class RewardRedemption {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne
    @JoinColumn(name = "family_id", nullable = false)
    private Family family;

    /** Null once the reward itself has been deleted by a restore. */
    @ManyToOne
    @JoinColumn(name = "reward_id")
    private Reward reward;

    @Column(name = "reward_title", nullable = false, length = 60)
    private String rewardTitle;

    @Column(nullable = false)
    private int points;

    @ManyToOne
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private RedemptionStatus status;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @ManyToOne
    @JoinColumn(name = "decided_by_member_id")
    private FamilyMember decidedBy;

    @Column(length = 200)
    private String reason;
}
