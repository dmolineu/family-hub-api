package com.familyhub.demo.dto;

/**
 * How many things wait for a parent, safe to show anyone (no details): kids' chores and
 * asks for rewards.
 */
public record PendingApprovalCountResponse(long count, long rewardRequests) {

    /** Chores only, the shape before the rewards shop. */
    public PendingApprovalCountResponse(long count) {
        this(count, 0);
    }
}
