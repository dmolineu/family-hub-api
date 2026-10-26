package com.familyhub.demo.controller;

import com.familyhub.demo.dto.ApiResponse;
import com.familyhub.demo.dto.ChoreActivityRequest;
import com.familyhub.demo.dto.ChoreActivityResponse;
import com.familyhub.demo.dto.ChoreApprovalRequest;
import com.familyhub.demo.dto.ChoreApprovalResultResponse;
import com.familyhub.demo.dto.ChoreBoardResponse;
import com.familyhub.demo.dto.ChorePointsResponse;
import com.familyhub.demo.dto.ChoreCurrentPeriodStateResponse;
import com.familyhub.demo.dto.ChoreTemplateResponse;
import com.familyhub.demo.dto.CreateChoreTemplateRequest;
import com.familyhub.demo.dto.UpdateChoreTemplateRequest;
import com.familyhub.demo.dto.PendingChoreApprovalResponse;
import com.familyhub.demo.dto.UpdateCurrentPeriodCompletionRequest;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.service.ChoreApprovalService;
import com.familyhub.demo.service.ChoreService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/chores")
@RequiredArgsConstructor
public class ChoreController {
    private final ChoreService choreService;
    private final ChoreApprovalService choreApprovalService;

    @GetMapping("/board")
    public ResponseEntity<ApiResponse<ChoreBoardResponse>> getBoard(@AuthenticationPrincipal Family family) {
        return ResponseEntity.ok(new ApiResponse<>(choreService.getBoard(family), ""));
    }

    @PostMapping("/templates")
    public ResponseEntity<ApiResponse<List<ChoreTemplateResponse>>> createTemplates(
            @Valid @RequestBody CreateChoreTemplateRequest request,
            @AuthenticationPrincipal Family family
    ) {
        // One request can create a chore for several members (or one unassigned chore),
        // so there is no single Location to point at.
        List<ChoreTemplateResponse> response = choreService.createTemplates(request, family);

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(response, "Chore template created successfully"));
    }

    @PatchMapping("/templates/{id}")
    public ResponseEntity<ApiResponse<ChoreTemplateResponse>> updateTemplate(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateChoreTemplateRequest request,
            @AuthenticationPrincipal Family family
    ) {
        return ResponseEntity.ok(new ApiResponse<>(
                choreService.updateTemplate(id, request, family),
                "Chore template updated successfully"
        ));
    }

    @PutMapping("/templates/{id}/current-period-completion")
    public ResponseEntity<ApiResponse<ChoreCurrentPeriodStateResponse>> completeCurrentPeriod(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCurrentPeriodCompletionRequest request,
            @AuthenticationPrincipal Family family
    ) {
        return ResponseEntity.ok(new ApiResponse<>(
                choreService.completeCurrentPeriod(id, request, family),
                "Chore completion updated successfully"
        ));
    }

    @DeleteMapping("/templates/{id}/current-period-completion")
    public ResponseEntity<ApiResponse<ChoreCurrentPeriodStateResponse>> uncompleteCurrentPeriod(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateCurrentPeriodCompletionRequest request,
            @AuthenticationPrincipal Family family
    ) {
        return ResponseEntity.ok(new ApiResponse<>(
                choreService.uncompleteCurrentPeriod(id, request, family),
                "Chore completion updated successfully"
        ));
    }

    /** Kids' chores waiting for a parent. */
    @GetMapping("/approvals")
    public ResponseEntity<ApiResponse<List<PendingChoreApprovalResponse>>> listPendingApprovals(
            @AuthenticationPrincipal Family family
    ) {
        return ResponseEntity.ok(new ApiResponse<>(choreApprovalService.listPending(family), ""));
    }

    /** A parent approves and rejects waiting chores with their own PIN. */
    @PostMapping("/approvals")
    public ResponseEntity<ApiResponse<ChoreApprovalResultResponse>> decideApprovals(
            @Valid @RequestBody ChoreApprovalRequest request,
            @AuthenticationPrincipal Family family
    ) {
        return ResponseEntity.ok(new ApiResponse<>(choreApprovalService.decide(family, request), "Chores decided"));
    }

    /** Every member's points balance. */
    @GetMapping("/points")
    public ResponseEntity<ApiResponse<List<ChorePointsResponse>>> getPoints(@AuthenticationPrincipal Family family) {
        return ResponseEntity.ok(new ApiResponse<>(choreApprovalService.points(family), ""));
    }

    /** The chore log; a POST so the parent's PIN stays out of URLs and access logs. */
    @PostMapping("/activity")
    public ResponseEntity<ApiResponse<List<ChoreActivityResponse>>> getActivity(
            @Valid @RequestBody ChoreActivityRequest request,
            @AuthenticationPrincipal Family family
    ) {
        return ResponseEntity.ok(new ApiResponse<>(choreApprovalService.activity(family, request), ""));
    }
}
