package com.familyhub.demo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** Open-Meteo endpoints and caching for the Home screen forecast. No API key is needed. */
@ConfigurationProperties(prefix = "weather")
@Configuration
@Data
public class WeatherProperties {
    private String forecastUrl = "https://api.open-meteo.com/v1/forecast";
    private String geocodingUrl = "https://geocoding-api.open-meteo.com/v1/search";
    /** How long a fetched forecast is reused before asking Open-Meteo again. */
    private Duration cacheTtl = Duration.ofMinutes(15);
    private int forecastDays = 5;
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(5);
}
