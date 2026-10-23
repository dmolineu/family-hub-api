package com.familyhub.demo.controller;

import com.familyhub.demo.config.SecurityConfig;
import com.familyhub.demo.dto.WeatherResponse;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.security.JwtAuthenticationEntryPoint;
import com.familyhub.demo.security.JwtAuthenticationFilter;
import com.familyhub.demo.security.WithMockFamily;
import com.familyhub.demo.service.FamilyService;
import com.familyhub.demo.service.JwtService;
import com.familyhub.demo.service.WeatherService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(WeatherController.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, JwtAuthenticationEntryPoint.class})
@ActiveProfiles("test")
class WeatherControllerTest {

    @Autowired
    MockMvc mockMvc;

    @MockitoBean
    JwtService jwtService;

    @MockitoBean
    FamilyService familyService;

    @MockitoBean
    WeatherService weatherService;

    @Test
    @WithMockFamily
    void currentWeather_returnsForecast() throws Exception {
        given(weatherService.currentWeather(any(Family.class))).willReturn(Optional.of(new WeatherResponse(
                "San Diego, California",
                Instant.parse("2026-10-23T18:00:00Z"),
                new WeatherResponse.CurrentWeather(72, 2, "Partly cloudy", true),
                List.of(new WeatherResponse.DailyWeather(LocalDate.of(2026, 10, 23), 2, "Partly cloudy", 75, 61, 10))
        )));

        mockMvc.perform(get("/api/weather"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.location").value("San Diego, California"))
                .andExpect(jsonPath("$.data.current.temperature").value(72))
                .andExpect(jsonPath("$.data.daily[0].date").value("2026-10-23"))
                .andExpect(jsonPath("$.data.daily[0].high").value(75));
    }

    @Test
    @WithMockFamily
    void currentWeather_withoutLocation_returns204() throws Exception {
        given(weatherService.currentWeather(any(Family.class))).willReturn(Optional.empty());

        mockMvc.perform(get("/api/weather"))
                .andExpect(status().isNoContent());
    }

    @Test
    void currentWeather_unauthenticated_returns401() throws Exception {
        mockMvc.perform(get("/api/weather"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.httpStatus").value(401));
    }
}
