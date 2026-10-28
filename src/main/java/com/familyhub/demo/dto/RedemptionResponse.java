package com.familyhub.demo.dto;

import com.familyhub.demo.model.RedemptionStatus;
import com.familyhub.demo.model.RewardRedemption;

import java.time.Instant;
import java.util.UUID;

/** A member's ask for a reward. */
public record RedemptionResponse(
        UUID id,
        UUID rewardId,
        String rewardTitle,
        UUID memberId,
        String memberName,
        int points,
        RedemptionStatus status,
        Instant requestedAt
) {

    /** Maps an ask; the reward reference is null once a restore removed the reward. */
    public static RedemptionResponse toDto(RewardRedemption redemption) {
        return new RedemptionResponse(
                redemption.getId(),
                redemption.getReward() == null ? null : redemption.getReward().getId(),
                redemption.getRewardTitle(),
                redemption.getMember().getId(),
                redemption.getMember().getName(),
                redemption.getPoints(),
                redemption.getStatus(),
                redemption.getRequestedAt()
        );
    }
}
