package com.familyhub.demo.service;

import com.familyhub.demo.config.WeatherProperties;
import com.familyhub.demo.dto.WeatherResponse;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.weather.Forecast;
import com.familyhub.demo.weather.WeatherProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static com.familyhub.demo.TestDataFactory.createFamily;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class WeatherServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-23T18:00:00Z");

    @Mock
    private WeatherProvider weatherProvider;

    private MutableClock clock;
    private WeatherService weatherService;
    private Family family;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(NOW);
        WeatherProperties properties = new WeatherProperties();
        properties.setCacheTtl(Duration.ofMinutes(15));
        weatherService = new WeatherService(weatherProvider, properties, clock);
        family = createFamily();
        family.setTimezone("America/Los_Angeles");
    }

    @Test
    void currentWeather_withoutLocation_isEmptyAndAsksNothing() {
        assertThat(weatherService.currentWeather(family)).isEmpty();
        verifyNoInteractions(weatherProvider);
    }

    @Test
    void currentWeather_mapsForecastToWholeDegreesAndDescriptions() {
        givenLocation();
        when(weatherProvider.forecast(32.7157, -117.1647, "America/Los_Angeles")).thenReturn(sampleForecast());

        Optional<WeatherResponse> result = weatherService.currentWeather(family);

        assertThat(result).isPresent();
        WeatherResponse weather = result.get();
        assertThat(weather.location()).isEqualTo("San Diego, California");
        assertThat(weather.updatedAt()).isEqualTo(NOW);
        assertThat(weather.current().temperature()).isEqualTo(72);
        assertThat(weather.current().description()).isEqualTo("Partly cloudy");
        assertThat(weather.current().isDay()).isTrue();
        assertThat(weather.daily()).hasSize(2);
        assertThat(weather.daily().get(0).date()).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(weather.daily().get(0).high()).isEqualTo(75);
        assertThat(weather.daily().get(0).low()).isEqualTo(61);
        assertThat(weather.daily().get(0).precipitationChance()).isEqualTo(10);
        assertThat(weather.daily().get(1).description()).isEqualTo("Rain");
        assertThat(weather.daily().get(1).precipitationChance()).isNull();
    }

    @Test
    void currentWeather_reusesTheForecastUntilTheCacheExpires() {
        givenLocation();
        when(weatherProvider.forecast(anyDouble(), anyDouble(), anyString())).thenReturn(sampleForecast());

        weatherService.currentWeather(family);
        clock.advance(Duration.ofMinutes(14));
        weatherService.currentWeather(family);
        verify(weatherProvider, times(1)).forecast(anyDouble(), anyDouble(), anyString());

        clock.advance(Duration.ofMinutes(2));
        weatherService.currentWeather(family);
        verify(weatherProvider, times(2)).forecast(anyDouble(), anyDouble(), anyString());
    }

    private void givenLocation() {
        family.setWeatherLocation("San Diego, California");
        family.setLatitude(32.7157);
        family.setLongitude(-117.1647);
    }

    private static Forecast sampleForecast() {
        return new Forecast(
                NOW,
                new Forecast.CurrentConditions(71.6, 2, true),
                List.of(
                        new Forecast.DailyForecast(LocalDate.of(2026, 10, 23), 2, 75.4, 60.5, 10),
                        new Forecast.DailyForecast(LocalDate.of(2026, 10, 24), 63, 68.0, 58.0, null)
                )
        );
    }

    /** A clock the test can move forward, for the cache TTL. */
    private static final class MutableClock extends Clock {
        private Instant now;

        MutableClock(Instant now) {
            this.now = now;
        }

        void advance(Duration by) {
            now = now.plus(by);
        }

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
