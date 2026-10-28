package com.familyhub.demo.controller;

import com.familyhub.demo.dto.ApiResponse;
import com.familyhub.demo.dto.ParentPinRequest;
import com.familyhub.demo.dto.RedemptionAskRequest;
import com.familyhub.demo.dto.RedemptionDecisionRequest;
import com.familyhub.demo.dto.RedemptionDecisionResultResponse;
import com.familyhub.demo.dto.RedemptionResponse;
import com.familyhub.demo.dto.RewardRequest;
import com.familyhub.demo.dto.RewardResponse;
import com.familyhub.demo.dto.RewardShopResponse;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.service.RewardService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The rewards shop. Looking and asking need no PIN; changing the catalogue and deciding
 * asks need a parent's own PIN, sent in the body so it never appears in a URL.
 */
@RestController
@RequestMapping("/api/rewards")
@RequiredArgsConstructor
public class RewardController {
    private final RewardService rewardService;

    /** What is on offer and which asks are waiting. */
    @GetMapping
    public ResponseEntity<ApiResponse<RewardShopResponse>> shop(@AuthenticationPrincipal Family family) {
        return ResponseEntity.ok(new ApiResponse<>(rewardService.shop(family), ""));
    }

    /** Adds a reward (parent PIN). */
    @PostMapping
    public ResponseEntity<ApiResponse<RewardResponse>> create(
            @Valid @RequestBody RewardRequest request,
            @AuthenticationPrincipal Family family
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(rewardService.create(family, request), "Reward added"));
    }

    /** Changes a reward (parent PIN). */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<RewardResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody RewardRequest request,
            @AuthenticationPrincipal Family family
    ) {
        return ResponseEntity.ok(new ApiResponse<>(rewardService.update(family, id, request), "Reward saved"));
    }

    /** Takes a reward out of the shop (parent PIN). */
    @PostMapping("/{id}/archive")
    public ResponseEntity<Void> archive(
            @PathVariable UUID id,
            @Valid @RequestBody ParentPinRequest request,
            @AuthenticationPrincipal Family family
    ) {
        rewardService.archive(family, id, request);
        return ResponseEntity.noContent().build();
    }

    /** A member asks for a reward; its points are held until a parent decides. */
    @PostMapping("/{id}/redemptions")
    public ResponseEntity<ApiResponse<RedemptionResponse>> ask(
            @PathVariable UUID id,
            @Valid @RequestBody RedemptionAskRequest request,
            @AuthenticationPrincipal Family family
    ) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(new ApiResponse<>(rewardService.ask(family, id, request), "Asked for the reward"));
    }

    /** The member who asked takes it back. */
    @PostMapping("/redemptions/{id}/cancel")
    public ResponseEntity<Void> cancel(
            @PathVariable UUID id,
            @Valid @RequestBody RedemptionAskRequest request,
            @AuthenticationPrincipal Family family
    ) {
        rewardService.cancel(family, id, request);
        return ResponseEntity.noContent().build();
    }

    /** A parent approves and declines waiting asks (parent PIN). */
    @PostMapping("/redemptions/decide")
    public ResponseEntity<ApiResponse<RedemptionDecisionResultResponse>> decide(
            @Valid @RequestBody RedemptionDecisionRequest request,
            @AuthenticationPrincipal Family family
    ) {
        return ResponseEntity.ok(new ApiResponse<>(rewardService.decide(family, request), ""));
    }
}
