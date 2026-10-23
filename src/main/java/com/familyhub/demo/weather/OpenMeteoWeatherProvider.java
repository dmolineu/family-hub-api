package com.familyhub.demo.weather;

import com.familyhub.demo.config.WeatherProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;

import java.net.http.HttpClient;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** Open-Meteo implementation: free for non-commercial use, no key, JSON over HTTPS. */
@Component
public class OpenMeteoWeatherProvider implements WeatherProvider {
    private final RestClient restClient;
    private final WeatherProperties properties;

    @Autowired
    public OpenMeteoWeatherProvider(WeatherProperties properties) {
        this(RestClient.builder().requestFactory(requestFactory(properties)).build(), properties);
    }

    /** Short timeouts: a slow weather service must never hold up the Home screen. */
    private static ClientHttpRequestFactory requestFactory(WeatherProperties properties) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getConnectTimeout())
                .build();
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(properties.getReadTimeout());
        return factory;
    }

    /** Test seam: a fully built client (for example bound to a MockRestServiceServer). */
    OpenMeteoWeatherProvider(RestClient restClient, WeatherProperties properties) {
        this.restClient = restClient;
        this.properties = properties;
    }

    @Override
    public Optional<GeoLocation> geocode(String query) {
        // Template variables are encoded strictly ("San Diego" -> San%20Diego).
        JsonNode body = restClient.get()
                .uri(properties.getGeocodingUrl() + "?name={name}&count=1&language=en&format=json", query)
                .retrieve()
                .body(JsonNode.class);
        JsonNode results = body == null ? null : body.path("results");
        if (results == null || !results.isArray() || results.isEmpty()) {
            return Optional.empty();
        }
        JsonNode first = results.get(0);
        return Optional.of(new GeoLocation(
                first.path("name").asString(),
                textOrNull(first, "admin1"),
                textOrNull(first, "country"),
                first.path("latitude").asDouble(),
                first.path("longitude").asDouble(),
                textOrNull(first, "timezone")
        ));
    }

    @Override
    public Forecast forecast(double latitude, double longitude, String timezone) {
        JsonNode body = restClient.get()
                .uri(properties.getForecastUrl()
                                + "?latitude={lat}&longitude={lon}"
                                + "&current=temperature_2m,weather_code,is_day"
                                + "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max"
                                + "&temperature_unit=fahrenheit&timezone={tz}&forecast_days={days}",
                        latitude, longitude, timezone, properties.getForecastDays())
                .retrieve()
                .body(JsonNode.class);
        if (body == null) {
            throw new IllegalStateException("Empty forecast response");
        }

        JsonNode current = body.path("current");
        Forecast.CurrentConditions conditions = new Forecast.CurrentConditions(
                current.path("temperature_2m").asDouble(),
                current.path("weather_code").asInt(),
                current.path("is_day").asInt() == 1
        );

        // Daily values are parallel arrays indexed like daily.time.
        JsonNode daily = body.path("daily");
        JsonNode dates = daily.path("time");
        List<Forecast.DailyForecast> days = new ArrayList<>();
        for (int i = 0; i < dates.size(); i++) {
            JsonNode chance = daily.path("precipitation_probability_max").path(i);
            days.add(new Forecast.DailyForecast(
                    LocalDate.parse(dates.get(i).asString()),
                    daily.path("weather_code").path(i).asInt(),
                    daily.path("temperature_2m_max").path(i).asDouble(),
                    daily.path("temperature_2m_min").path(i).asDouble(),
                    chance.isMissingNode() || chance.isNull() ? null : chance.asInt()
            ));
        }
        return new Forecast(Instant.now(), conditions, List.copyOf(days));
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.path(field);
        return value.isMissingNode() || value.isNull() ? null : value.asString();
    }
}
