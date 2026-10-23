package com.familyhub.demo.weather;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

/** Provider-neutral forecast, temperatures already in the family's unit (Fahrenheit). */
public record Forecast(Instant fetchedAt, CurrentConditions current, List<DailyForecast> daily) {
    public record CurrentConditions(double temperature, int weatherCode, boolean isDay) {}

    public record DailyForecast(
            LocalDate date,
            int weatherCode,
            double high,
            double low,
            /** 0–100, or null when the provider has no value. */
            Integer precipitationChance
    ) {}
}
