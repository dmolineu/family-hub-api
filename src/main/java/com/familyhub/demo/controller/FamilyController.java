package com.familyhub.demo.controller;

import com.familyhub.demo.dto.ApiResponse;
import com.familyhub.demo.dto.FamilyRequest;
import com.familyhub.demo.dto.FamilyResponse;
import com.familyhub.demo.dto.ParentPinRequest;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.service.FamilyService;
import com.familyhub.demo.service.ParentPinService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class FamilyController {
    private final FamilyService familyService;
    private final ParentPinService parentPinService;

    @GetMapping("/family")
    ResponseEntity<ApiResponse<FamilyResponse>> findFamilyById(@AuthenticationPrincipal Family family) {
        FamilyResponse response = familyService.findFamilyResponse(family.getId());
        return ResponseEntity.ok(new ApiResponse<>(response, "Family Found"));
    }

    @PutMapping("/family")
    ResponseEntity<ApiResponse<FamilyResponse>> updateFamily(@AuthenticationPrincipal Family family, @RequestBody @Valid FamilyRequest update) {
        FamilyResponse response = familyService.updateFamily(family.getId(), update);
        return ResponseEntity.ok(new ApiResponse<>(response, "Update"));
    }

    /**
     * Checks a parent's face and PIN to unlock a screen such as Family Settings or
     * Preferences. Wrong tries count, and lock that parent, exactly as when approving
     * chores; the changes made once unlocked still carry the PIN where the server needs it.
     */
    @PostMapping("/family/unlock")
    ResponseEntity<Void> unlock(@AuthenticationPrincipal Family family, @RequestBody @Valid ParentPinRequest request) {
        parentPinService.verify(family, request.approverMemberId(), request.pin());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/family")
    ResponseEntity<Void> deleteFamily(@AuthenticationPrincipal Family family) {
        familyService.deleteFamily(family.getId());
        return ResponseEntity.noContent().build();
    }
}
