package com.familyhub.demo.model;

/** Where a member's ask for a reward stands. */
public enum RedemptionStatus {
    /** Waiting for a parent; its points are held. */
    PENDING,
    /** A parent approved it and the points were spent. */
    APPROVED,
    /** A parent said no; nothing was spent. */
    DECLINED,
    /** The member took it back before anyone decided. */
    CANCELLED
}
