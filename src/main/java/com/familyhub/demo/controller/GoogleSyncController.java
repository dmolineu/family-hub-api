package com.familyhub.demo.controller;

import com.familyhub.demo.dto.ApiResponse;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.service.FamilyMemberService;
import com.familyhub.demo.service.GoogleCalendarAdminService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

// TODO: Add rate limiting or cooldown check (e.g., reject sync if lastSyncedAt < 60s ago)
//  to prevent excessive Google API calls and database churn from rapid-fire requests.
@RestController
@RequestMapping("/api/google/sync")
@RequiredArgsConstructor
public class GoogleSyncController {
    private final FamilyMemberService familyMemberService;
    private final GoogleCalendarAdminService calendarAdmin;

    /**
     * "Sync now": reads the member's Google calendar list again and syncs the family's
     * switched-on calendars (Phase 4), off the request thread.
     *
     * @param memberId the member whose account to read
     * @param family   the signed-in family
     * @return accepted
     */
    @PostMapping("/{memberId}")
    public ResponseEntity<ApiResponse<Void>> syncMember(
            @PathVariable UUID memberId,
            @AuthenticationPrincipal Family family) {
        familyMemberService.findById(family, memberId);

        calendarAdmin.refreshMember(family, memberId);
        return ResponseEntity.accepted().body(new ApiResponse<>(null, "Sync started"));
    }
}
