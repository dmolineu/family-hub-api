package com.familyhub.demo.dto;

/**
 * How many chores a decision actually changed. Chores that were unticked or decided by
 * someone else in the meantime are skipped rather than failing the whole batch.
 */
public record ChoreApprovalResultResponse(int approved, int rejected) {
}
