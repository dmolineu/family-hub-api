package com.familyhub.demo.dto;

import java.util.List;

/**
 * The shop as anyone sees it, with no PIN: what is on offer and which asks are waiting
 * (their points are held). Deciding the waiting asks needs a parent's PIN.
 */
public record RewardShopResponse(List<RewardResponse> rewards, List<RedemptionResponse> waiting) {
}
