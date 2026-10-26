package com.familyhub.demo.dto;

import com.familyhub.demo.model.MemberRole;

import java.util.UUID;

/** A member's points balance: the sum of their lines in the chore log. */
public record ChorePointsResponse(UUID memberId, String memberName, MemberRole role, int points) {
}
