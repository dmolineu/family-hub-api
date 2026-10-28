package com.familyhub.demo.controller;

import com.familyhub.demo.dto.ApiResponse;
import com.familyhub.demo.dto.InboxApproveRequest;
import com.familyhub.demo.dto.InboxImportResponse;
import com.familyhub.demo.dto.InboxSenderRequest;
import com.familyhub.demo.dto.InboxSenderResponse;
import com.familyhub.demo.dto.InboxStatusResponse;
import com.familyhub.demo.dto.ParentPinRequest;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.service.InboxReviewService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** The family inbox: what it did and what waits, and a parent's decisions (each with a PIN). */
@RestController
@RequestMapping("/api/inbox")
@RequiredArgsConstructor
public class InboxController {
    private final InboxReviewService reviewService;

    /** The inbox's address and state, who may send, and the latest imports. */
    @GetMapping
    public ResponseEntity<ApiResponse<InboxStatusResponse>> status(@AuthenticationPrincipal Family family) {
        return ResponseEntity.ok(new ApiResponse<>(reviewService.status(family), ""));
    }

    /** Puts a proposed event on the calendar, with any corrections (parent PIN). */
    @PostMapping("/imports/{id}/approve")
    public ResponseEntity<ApiResponse<InboxImportResponse>> approve(
            @PathVariable UUID id,
            @Valid @RequestBody InboxApproveRequest request,
            @AuthenticationPrincipal Family family
    ) {
        return ResponseEntity.ok(new ApiResponse<>(reviewService.approve(family, id, request), "Added to the calendar"));
    }

    /** Turns a proposed event down (parent PIN). */
    @PostMapping("/imports/{id}/reject")
    public ResponseEntity<ApiResponse<InboxImportResponse>> reject(
            @PathVariable UUID id,
            @Valid @RequestBody ParentPinRequest request,
            @AuthenticationPrincipal Family family
    ) {
        return ResponseEntity.ok(new ApiResponse<>(reviewService.reject(family, id, request), "Turned down"));
    }

    /** Takes off something the inbox added (parent PIN). */
    @PostMapping("/imports/{id}/undo")
    public ResponseEntity<ApiResponse<InboxImportResponse>> undo(
            @PathVariable UUID id,
            @Valid @RequestBody ParentPinRequest request,
            @AuthenticationPrincipal Family family
    ) {
        return ResponseEntity.ok(new ApiResponse<>(reviewService.undo(family, id, request), "Taken off"));
    }

    /** Lets another address send to the inbox (parent PIN). */
    @PostMapping("/senders")
    public ResponseEntity<ApiResponse<InboxSenderResponse>> addSender(
            @Valid @RequestBody InboxSenderRequest request,
            @AuthenticationPrincipal Family family
    ) {
        return ResponseEntity.ok(new ApiResponse<>(reviewService.addSender(family, request), "Address allowed"));
    }

    /** Stops an added address from sending (parent PIN). */
    @PostMapping("/senders/{id}/remove")
    public ResponseEntity<Void> removeSender(
            @PathVariable UUID id,
            @Valid @RequestBody ParentPinRequest request,
            @AuthenticationPrincipal Family family
    ) {
        reviewService.removeSender(family, id, request);
        return ResponseEntity.noContent().build();
    }
}
