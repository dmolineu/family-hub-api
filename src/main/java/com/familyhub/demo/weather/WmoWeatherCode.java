package com.familyhub.demo.weather;

import java.util.Map;

/** WMO 4677 weather interpretation codes as Open-Meteo emits them. */
public final class WmoWeatherCode {
    private static final Map<Integer, String> DESCRIPTIONS = Map.ofEntries(
            Map.entry(0, "Clear sky"),
            Map.entry(1, "Mainly clear"),
            Map.entry(2, "Partly cloudy"),
            Map.entry(3, "Overcast"),
            Map.entry(45, "Fog"),
            Map.entry(48, "Icy fog"),
            Map.entry(51, "Light drizzle"),
            Map.entry(53, "Drizzle"),
            Map.entry(55, "Heavy drizzle"),
            Map.entry(56, "Freezing drizzle"),
            Map.entry(57, "Freezing drizzle"),
            Map.entry(61, "Light rain"),
            Map.entry(63, "Rain"),
            Map.entry(65, "Heavy rain"),
            Map.entry(66, "Freezing rain"),
            Map.entry(67, "Freezing rain"),
            Map.entry(71, "Light snow"),
            Map.entry(73, "Snow"),
            Map.entry(75, "Heavy snow"),
            Map.entry(77, "Snow grains"),
            Map.entry(80, "Light showers"),
            Map.entry(81, "Showers"),
            Map.entry(82, "Heavy showers"),
            Map.entry(85, "Snow showers"),
            Map.entry(86, "Heavy snow showers"),
            Map.entry(95, "Thunderstorm"),
            Map.entry(96, "Thunderstorm with hail"),
            Map.entry(99, "Thunderstorm with hail")
    );

    private WmoWeatherCode() {}

    /** A short label for the code; unknown codes read "Unknown" rather than failing. */
    public static String describe(int code) {
        return DESCRIPTIONS.getOrDefault(code, "Unknown");
    }
}
