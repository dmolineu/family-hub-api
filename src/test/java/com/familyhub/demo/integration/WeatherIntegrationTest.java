package com.familyhub.demo.integration;

import com.familyhub.demo.config.TestcontainersConfig;
import com.familyhub.demo.weather.Forecast;
import com.familyhub.demo.weather.GeoLocation;
import com.familyhub.demo.weather.WeatherProvider;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.BDDMockito.given;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@ActiveProfiles("test")
class WeatherIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private WeatherProvider weatherProvider;

    @Test
    void settingALocationUnlocksTheForecast() throws Exception {
        String token = registerAndToken();
        given(weatherProvider.geocode("San Diego")).willReturn(Optional.of(
                new GeoLocation("San Diego", "California", "United States", 32.7157, -117.1647, "America/Los_Angeles")
        ));
        given(weatherProvider.forecast(anyDouble(), anyDouble(), anyString())).willReturn(new Forecast(
                Instant.parse("2026-10-23T18:00:00Z"),
                new Forecast.CurrentConditions(71.6, 2, true),
                List.of(new Forecast.DailyForecast(LocalDate.of(2026, 10, 23), 2, 75.4, 60.5, 10))
        ));

        mockMvc.perform(get("/api/weather").header("Authorization", "Bearer " + token))
                .andExpect(status().isNoContent());

        mockMvc.perform(put("/api/family")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"weatherLocation\": \"San Diego\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.weatherLocation").value("San Diego, California"));

        mockMvc.perform(get("/api/weather").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.location").value("San Diego, California"))
                .andExpect(jsonPath("$.data.current.temperature").value(72))
                .andExpect(jsonPath("$.data.daily[0].high").value(75));
    }

    private String registerAndToken() throws Exception {
        String username = "weather_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        String body = mockMvc.perform(post("/api/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"username": "%s", "password": "password123", "familyName": "Weather Family",
                                 "members": [{"name": "Parent", "color": "CORAL"}], "timezone": "America/Los_Angeles"}
                                """.formatted(username)))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(body, "$.data.token");
    }
}
