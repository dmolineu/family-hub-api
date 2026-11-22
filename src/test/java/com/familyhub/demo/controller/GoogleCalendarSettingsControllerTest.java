package com.familyhub.demo.controller;

import com.familyhub.demo.config.SecurityConfig;
import com.familyhub.demo.dto.calendar.GoogleCalendarSettingResponse;
import com.familyhub.demo.dto.calendar.GoogleCalendarSettingsResponse;
import com.familyhub.demo.dto.calendar.GoogleHealthResponse;
import com.familyhub.demo.dto.calendar.UpdateGoogleCalendarSettingsRequest;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.security.JwtAuthenticationEntryPoint;
import com.familyhub.demo.security.JwtAuthenticationFilter;
import com.familyhub.demo.security.WithMockFamily;
import com.familyhub.demo.service.FamilyService;
import com.familyhub.demo.service.GoogleCalendarAdminService;
import com.familyhub.demo.service.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Family Settings' Google calendar list, its changes and the calendar's health banner
 * over HTTP (Phase 4).
 */
@WebMvcTest(GoogleCalendarSettingsController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class})
@ActiveProfiles("test")
class GoogleCalendarSettingsControllerTest {

    private static final UUID HUB = UUID.randomUUID();
    private static final UUID MAYA = UUID.randomUUID();

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtService jwtService;

    @MockitoBean
    private FamilyService familyService;

    @MockitoBean
    private GoogleCalendarAdminService admin;

    private static GoogleCalendarSettingsResponse settings() {
        return new GoogleCalendarSettingsResponse(List.of(new GoogleCalendarSettingResponse(HUB,
                "hub@group.calendar.google.com", "Hub", "#9fc6e7", "America/Los_Angeles", true, "owner", true,
                List.of(MAYA), true, Instant.parse("2026-11-21T18:00:00Z"), null)), HUB);
    }

    @Test
    @WithMockFamily
    void theCalendarsAreListedWithTheirLanesAndTheDefault() throws Exception {
        when(admin.settings(any())).thenReturn(settings());

        mockMvc.perform(get("/api/google/calendars"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.defaultCalendarId").value(HUB.toString()))
                .andExpect(jsonPath("$.data.calendars[0].summary").value("Hub"))
                .andExpect(jsonPath("$.data.calendars[0].accessRole").value("owner"))
                .andExpect(jsonPath("$.data.calendars[0].writable").value(true))
                .andExpect(jsonPath("$.data.calendars[0].isDefault").value(true))
                .andExpect(jsonPath("$.data.calendars[0].laneMemberIds[0]").value(MAYA.toString()));
    }

    @Test
    @WithMockFamily
    void aChangeIsAppliedAndARefusedDefaultIsA400() throws Exception {
        when(admin.update(any(), any(UpdateGoogleCalendarSettingsRequest.class))).thenReturn(settings());
        String body = """
                {"calendars":[{"id":"%s","enabled":true,"laneMemberIds":["%s"]}],"defaultCalendarId":"%s"}
                """.formatted(HUB, MAYA, HUB);

        mockMvc.perform(put("/api/google/calendars").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.calendars[0].enabled").value(true));

        when(admin.update(any(), any(UpdateGoogleCalendarSettingsRequest.class)))
                .thenThrow(new BadRequestException("New events can only go on a calendar the hub may write to."));
        mockMvc.perform(put("/api/google/calendars").contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isBadRequest());
    }

    @Test
    @WithMockFamily
    void healthSaysWhetherAnAccountMustSignInAgain() throws Exception {
        when(admin.health(any())).thenReturn(new GoogleHealthResponse(true, true, false,
                Instant.parse("2026-11-21T18:00:00Z"), List.of()));

        mockMvc.perform(get("/api/google/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.connected").value(true))
                .andExpect(jsonPath("$.data.needsReauth").value(true))
                .andExpect(jsonPath("$.data.writeScopeMissing").value(false))
                .andExpect(jsonPath("$.data.oldestSyncAt").value("2026-11-21T18:00:00Z"));
    }

    @Test
    void signingInIsRequired() throws Exception {
        mockMvc.perform(get("/api/google/calendars")).andExpect(status().isUnauthorized());
    }
}
