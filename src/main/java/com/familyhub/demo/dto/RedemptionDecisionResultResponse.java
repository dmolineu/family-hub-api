package com.familyhub.demo.dto;

/** How many waiting asks a decision approved and declined; asks no longer waiting are skipped. */
public record RedemptionDecisionResultResponse(int approved, int declined) {
}
