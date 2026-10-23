package com.familyhub.demo.dto;

import java.util.List;

/** {@code member} is null for the "anyone" group that holds unassigned chores. */
public record ChoreAssigneeGroupResponse(
        FamilyMemberResponse member,
        Summary summary,
        List<ChoreBoardItemResponse> chores
) {
    public record Summary(int total, int completed, int remaining) {
    }
}
