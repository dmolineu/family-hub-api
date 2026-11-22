package com.familyhub.demo.controller;

import com.familyhub.demo.config.SecurityConfig;
import com.familyhub.demo.dto.calendar.CalendarEventResponse;
import com.familyhub.demo.dto.calendar.CalendarWriteRequest;
import com.familyhub.demo.exception.CalendarWriteException;
import com.familyhub.demo.security.JwtAuthenticationEntryPoint;
import com.familyhub.demo.security.JwtAuthenticationFilter;
import com.familyhub.demo.security.WithMockFamily;
import com.familyhub.demo.service.CalendarQueryService;
import com.familyhub.demo.service.CalendarWriteService;
import com.familyhub.demo.service.FamilyService;
import com.familyhub.demo.service.JwtService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The calendar API over HTTP (Phase 4): reads from the cache of Google, writes through
 * Google, and Google's refusals as error codes the app can explain, with Retry-After
 * when Google asked to wait. None of them is a 401, which would sign the app out.
 */
@WebMvcTest(CalendarEventController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class})
@ActiveProfiles("test")
class CalendarEventControllerTest {

    private static final UUID EVENT = UUID.randomUUID();
    private static final UUID MEMBER = UUID.randomUUID();
    private static final UUID CALENDAR = UUID.randomUUID();

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    JwtService jwtService;

    @MockitoBean
    FamilyService familyService;

    @MockitoBean
    CalendarQueryService query;

    @MockitoBean
    CalendarWriteService writes;

    private static CalendarEventResponse swim() {
        return new CalendarEventResponse(EVENT, EVENT.toString(), CALENDAR, "#9fc6e7", "abc", "\"3\"", null, null,
                false, null, "Swim", null, "Pool", null, false, "2026-12-03T17:00:00Z", "2026-12-03T18:00:00Z",
                "America/Los_Angeles", List.of(MEMBER), "confirmed", true);
    }

    private static final String SWIM = """
            {"memberIds": ["%s"], "title": "Swim", "location": "Pool", "allDay": false,
             "start": "2026-12-03T17:00:00Z", "end": "2026-12-03T18:00:00Z", "etag": "\\"3\\""}
            """.formatted(MEMBER);

    @Test
    @WithMockFamily
    void aWindowIsReadFromTheCacheWithItsWireFormat() throws Exception {
        when(query.eventsFor(any(), eq(LocalDate.parse("2026-12-01")), eq(LocalDate.parse("2026-12-31")),
                eq(MEMBER))).thenReturn(List.of(swim()));

        mockMvc.perform(get("/api/calendar/events").param("startDate", "2026-12-01")
                        .param("endDate", "2026-12-31").param("memberId", MEMBER.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].id").value(EVENT.toString()))
                .andExpect(jsonPath("$.data[0].start").value("2026-12-03T17:00:00Z"))
                .andExpect(jsonPath("$.data[0].allDay").value(false))
                .andExpect(jsonPath("$.data[0].memberIds[0]").value(MEMBER.toString()))
                .andExpect(jsonPath("$.data[0].canEdit").value(true))
                .andExpect(jsonPath("$.data[0].etag").value("\"3\""));
    }

    @Test
    @WithMockFamily
    void aNewEventIsCreatedAndItsAddressGiven() throws Exception {
        when(writes.create(any(), any(CalendarWriteRequest.class))).thenReturn(swim());

        mockMvc.perform(post("/api/calendar/events").contentType(MediaType.APPLICATION_JSON).content(SWIM))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "/api/calendar/events/" + EVENT))
                .andExpect(jsonPath("$.data.title").value("Swim"));

        ArgumentCaptor<CalendarWriteRequest> sent = ArgumentCaptor.forClass(CalendarWriteRequest.class);
        verify(writes).create(any(), sent.capture());
        assertThat(sent.getValue().start()).isEqualTo(Instant.parse("2026-12-03T17:00:00Z"));
        assertThat(sent.getValue().memberIds()).containsExactly(MEMBER);
    }

    @Test
    @WithMockFamily
    void aChangeADeleteAndOneDateOrTheFollowingOnesGoToTheirOwnRoutes() throws Exception {
        when(writes.update(any(), eq(EVENT), any())).thenReturn(swim());
        when(writes.updateOccurrence(any(), eq(EVENT), eq("20261114T000000Z"), any())).thenReturn(swim());
        when(writes.updateFollowing(any(), eq(EVENT), eq("20261121T000000Z"), any())).thenReturn(swim());

        mockMvc.perform(put("/api/calendar/events/{id}", EVENT).contentType(MediaType.APPLICATION_JSON).content(SWIM))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/calendar/events/{id}", EVENT).param("etag", "\"3\""))
                .andExpect(status().isNoContent());
        mockMvc.perform(put("/api/calendar/events/{id}/instances/{start}", EVENT, "20261114T000000Z")
                        .contentType(MediaType.APPLICATION_JSON).content(SWIM))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/calendar/events/{id}/instances/{start}", EVENT, "20261114T000000Z"))
                .andExpect(status().isNoContent());
        mockMvc.perform(put("/api/calendar/events/{id}/instances/{start}/following", EVENT, "20261121T000000Z")
                        .contentType(MediaType.APPLICATION_JSON).content(SWIM))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/calendar/events/{id}/instances/{start}/following", EVENT, "20261121T000000Z"))
                .andExpect(status().isNoContent());

        verify(writes).delete(any(), eq(EVENT), eq("\"3\""));
        verify(writes).deleteOccurrence(any(), eq(EVENT), eq("20261114T000000Z"));
        verify(writes).deleteFollowing(any(), eq(EVENT), eq("20261121T000000Z"));
    }

    @Test
    @WithMockFamily
    void aDeleteWithoutAnEtagDeletesWhateverGoogleHolds() throws Exception {
        mockMvc.perform(delete("/api/calendar/events/{id}", EVENT)).andExpect(status().isNoContent());

        verify(writes).delete(any(), eq(EVENT), isNull());
    }

    @Test
    @WithMockFamily
    void googlesRefusalsBecomeCodesWithRetryAfterAndNeverA401() throws Exception {
        when(writes.create(any(), any())).thenThrow(new CalendarWriteException(
                CalendarWriteException.Code.GOOGLE_RATE_LIMITED, "Google asked the hub to slow down.",
                Duration.ofSeconds(90)));
        mockMvc.perform(post("/api/calendar/events").contentType(MediaType.APPLICATION_JSON).content(SWIM))
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "90"))
                .andExpect(jsonPath("$.code").value("GOOGLE_RATE_LIMITED"))
                .andExpect(jsonPath("$.message").value("Google asked the hub to slow down."));

        when(writes.update(any(), any(), any())).thenThrow(new CalendarWriteException(
                CalendarWriteException.Code.EVENT_CONFLICT, "This event changed in Google.", null));
        mockMvc.perform(put("/api/calendar/events/{id}", EVENT).contentType(MediaType.APPLICATION_JSON).content(SWIM))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_CONFLICT"));

        when(writes.updateOccurrence(any(), any(), any(), any())).thenThrow(new CalendarWriteException(
                CalendarWriteException.Code.GOOGLE_REAUTH_REQUIRED, "Connect Google again.", null));
        mockMvc.perform(put("/api/calendar/events/{id}/instances/{start}", EVENT, "20261114T000000Z")
                        .contentType(MediaType.APPLICATION_JSON).content(SWIM))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GOOGLE_REAUTH_REQUIRED"));
    }

    @Test
    @WithMockFamily
    void anEventWithNoMembersOrTitleIsRefusedBeforeGoogleIsAsked() throws Exception {
        mockMvc.perform(post("/api/calendar/events").contentType(MediaType.APPLICATION_JSON).content("""
                        {"memberIds": [], "title": "", "allDay": true, "startDate": "2026-12-03", "endDate": "2026-12-04"}
                        """))
                .andExpect(status().isBadRequest());
    }
}
