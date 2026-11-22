package com.familyhub.demo.controller;

import com.familyhub.demo.dto.ApiResponse;
import com.familyhub.demo.dto.calendar.GoogleCalendarSettingsResponse;
import com.familyhub.demo.dto.calendar.GoogleHealthResponse;
import com.familyhub.demo.dto.calendar.UpdateGoogleCalendarSettingsRequest;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.service.GoogleCalendarAdminService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The family's Google calendars in Family Settings, and the calendar's health banner
 * (Phase 4). Family Settings asks for a parent's PIN when it opens.
 */
@RestController
@RequestMapping("/api/google")
public class GoogleCalendarSettingsController {

    private final GoogleCalendarAdminService admin;

    /**
     * Creates the controller.
     *
     * @param admin the calendar settings
     */
    public GoogleCalendarSettingsController(GoogleCalendarAdminService admin) {
        this.admin = admin;
    }

    /**
     * Every calendar the family's accounts can see, and where new events go.
     *
     * @param family the signed-in family
     * @return the calendars
     */
    @GetMapping("/calendars")
    public ResponseEntity<ApiResponse<GoogleCalendarSettingsResponse>> calendars(
            @AuthenticationPrincipal Family family) {
        return ResponseEntity.ok(new ApiResponse<>(admin.settings(family), ""));
    }

    /**
     * Switches calendars on or off, sets their lanes, and picks the default.
     *
     * @param family  the signed-in family
     * @param request the changes
     * @return the calendars after the change
     */
    @PutMapping("/calendars")
    public ResponseEntity<ApiResponse<GoogleCalendarSettingsResponse>> update(
            @AuthenticationPrincipal Family family, @Valid @RequestBody UpdateGoogleCalendarSettingsRequest request) {
        return ResponseEntity.ok(new ApiResponse<>(admin.update(family, request), "Calendars updated"));
    }

    /**
     * Whether the calendars are up to date and every account can write.
     *
     * @param family the signed-in family
     * @return the health
     */
    @GetMapping("/health")
    public ResponseEntity<ApiResponse<GoogleHealthResponse>> health(@AuthenticationPrincipal Family family) {
        return ResponseEntity.ok(new ApiResponse<>(admin.health(family), ""));
    }
}
