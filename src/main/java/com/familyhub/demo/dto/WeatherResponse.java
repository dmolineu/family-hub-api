package com.familyhub.demo.dto;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** The Home screen forecast. Temperatures are whole degrees Fahrenheit. */
public record WeatherResponse(
        String location,
        Instant updatedAt,
        CurrentWeather current,
        List<DailyWeather> daily
) {
    public record CurrentWeather(int temperature, int weatherCode, String description, boolean isDay) {}

    public record DailyWeather(
            LocalDate date,
            int weatherCode,
            String description,
            int high,
            int low,
            Integer precipitationChance
    ) {}
}
