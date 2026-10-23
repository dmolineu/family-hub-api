package com.familyhub.demo.controller;

import com.familyhub.demo.dto.ApiResponse;
import com.familyhub.demo.dto.WeatherResponse;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.service.WeatherService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/weather")
@RequiredArgsConstructor
public class WeatherController {
    private final WeatherService weatherService;

    /** 204 until the family sets a weather location in settings. */
    @GetMapping
    public ResponseEntity<ApiResponse<WeatherResponse>> currentWeather(@AuthenticationPrincipal Family family) {
        return weatherService.currentWeather(family)
                .map(weather -> ResponseEntity.ok(new ApiResponse<>(weather, "")))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
