package com.familyhub.demo.service;

import com.familyhub.demo.config.WeatherProperties;
import com.familyhub.demo.dto.WeatherResponse;
import com.familyhub.demo.exception.ServiceUnavailableException;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.weather.Forecast;
import com.familyhub.demo.weather.WeatherProvider;
import com.familyhub.demo.weather.WmoWeatherCode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Serves the family's forecast from a short-lived cache in front of the provider, so a
 * wall display polling every minute costs Open-Meteo one request per cache period.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WeatherService {
    private final WeatherProvider weatherProvider;
    private final WeatherProperties properties;
    private final Clock clock;
    private final Map<String, CachedForecast> cache = new ConcurrentHashMap<>();

    /** Empty when the family has not set a location yet. */
    public Optional<WeatherResponse> currentWeather(Family family) {
        if (family.getLatitude() == null || family.getLongitude() == null) {
            return Optional.empty();
        }
        String timezone = FamilyTimezoneResolver.resolveStoredTimezoneOrDefault(family.getTimezone());
        Forecast forecast = cachedForecast(family.getLatitude(), family.getLongitude(), timezone);
        return Optional.of(toResponse(family.getWeatherLocation(), forecast));
    }

    private Forecast cachedForecast(double latitude, double longitude, String timezone) {
        String key = String.format("%.4f,%.4f,%s", latitude, longitude, timezone);
        Instant now = clock.instant();
        CachedForecast cached = cache.get(key);
        if (cached != null && Duration.between(cached.fetchedAt(), now).compareTo(properties.getCacheTtl()) < 0) {
            return cached.forecast();
        }
        try {
            Forecast fresh = weatherProvider.forecast(latitude, longitude, timezone);
            cache.put(key, new CachedForecast(now, fresh));
            return fresh;
        } catch (RuntimeException ex) {
            // A stale forecast beats an empty tile; only fail when there is nothing to show.
            if (cached != null) {
                log.warn("Weather provider failed; serving the cached forecast from {}", cached.fetchedAt(), ex);
                return cached.forecast();
            }
            throw new ServiceUnavailableException("Weather service is unavailable right now.", ex);
        }
    }

    private WeatherResponse toResponse(String location, Forecast forecast) {
        return new WeatherResponse(
                location,
                forecast.fetchedAt(),
                new WeatherResponse.CurrentWeather(
                        round(forecast.current().temperature()),
                        forecast.current().weatherCode(),
                        WmoWeatherCode.describe(forecast.current().weatherCode()),
                        forecast.current().isDay()
                ),
                forecast.daily().stream()
                        .map(day -> new WeatherResponse.DailyWeather(
                                day.date(),
                                day.weatherCode(),
                                WmoWeatherCode.describe(day.weatherCode()),
                                round(day.high()),
                                round(day.low()),
                                day.precipitationChance()
                        ))
                        .toList()
        );
    }

    private static int round(double value) {
        return (int) Math.round(value);
    }

    private record CachedForecast(Instant fetchedAt, Forecast forecast) {}
}
