package com.familyhub.demo.controller;

import com.familyhub.demo.dto.ApiResponse;
import com.familyhub.demo.dto.FamilyMemberRequest;
import com.familyhub.demo.dto.FamilyMemberResponse;
import com.familyhub.demo.dto.MemberPinRequest;
import com.familyhub.demo.dto.MemberRoleRequest;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.service.FamilyMemberService;
import com.familyhub.demo.service.ParentPinService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/family/members")
@RequiredArgsConstructor
public class FamilyMemberController {
    private final FamilyMemberService familyMemberService;
    private final ParentPinService parentPinService;


    @GetMapping
    public ResponseEntity<ApiResponse<List<FamilyMemberResponse>>> getFamilyMembers(@AuthenticationPrincipal Family family) {
        List<FamilyMemberResponse> members = familyMemberService.findAllMembers(family);

        return ResponseEntity.ok(new ApiResponse<>(members, "Get members"));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<FamilyMemberResponse>> getFamilyMember(
            @AuthenticationPrincipal Family family,
            @PathVariable UUID id
    ) {
        return ResponseEntity.ok(new ApiResponse<>(familyMemberService.findById(family, id), "Get member by id"));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<FamilyMemberResponse>> addFamilyMember(
            @AuthenticationPrincipal Family family,
            @Valid @RequestBody FamilyMemberRequest request) {

        FamilyMemberResponse response = familyMemberService.addFamilyMember(family, request);
        URI location = URI.create("/api/family/members/" + response.id());

        return ResponseEntity.created(location).body(
                new ApiResponse<>(response, "Family Member Added")
        );
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<FamilyMemberResponse>> updateFamilyMember(
            @AuthenticationPrincipal Family family,
            @Valid @RequestBody FamilyMemberRequest familyMemberRequest,
            @PathVariable UUID id
    ) {
        FamilyMemberResponse response = familyMemberService.updateFamilyMember(family, id, familyMemberRequest);
        return ResponseEntity.ok(new ApiResponse<>(response, "Family Member Updated"));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteFamilyMember(
            @AuthenticationPrincipal Family family,
            @PathVariable UUID id
    ) {
        familyMemberService.deleteFamilyMember(family, id);
        return ResponseEntity.noContent().build();
    }

    /** Makes a member a parent or a kid; needs the family password. */
    @PutMapping("/{id}/role")
    public ResponseEntity<ApiResponse<FamilyMemberResponse>> setRole(
            @AuthenticationPrincipal Family family,
            @PathVariable UUID id,
            @Valid @RequestBody MemberRoleRequest request
    ) {
        return ResponseEntity.ok(new ApiResponse<>(parentPinService.setRole(family, id, request), "Role updated"));
    }

    /** Sets a parent's own PIN; needs the family password. */
    @PutMapping("/{id}/pin")
    public ResponseEntity<Void> setPin(
            @AuthenticationPrincipal Family family,
            @PathVariable UUID id,
            @Valid @RequestBody MemberPinRequest request
    ) {
        parentPinService.setPin(family, id, request);
        return ResponseEntity.noContent().build();
    }
}
