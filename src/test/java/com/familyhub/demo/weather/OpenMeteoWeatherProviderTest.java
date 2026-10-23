package com.familyhub.demo.weather;

import com.familyhub.demo.config.WeatherProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import java.time.LocalDate;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.queryParam;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;
import static org.hamcrest.Matchers.startsWith;

class OpenMeteoWeatherProviderTest {
    private MockRestServiceServer server;
    private OpenMeteoWeatherProvider provider;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        WeatherProperties properties = new WeatherProperties();
        properties.setForecastDays(2);
        provider = new OpenMeteoWeatherProvider(builder.build(), properties);
    }

    @Test
    void geocode_returnsTheFirstMatchWithRegionAndZone() {
        server.expect(requestTo(startsWith("https://geocoding-api.open-meteo.com/v1/search")))
                .andExpect(queryParam("name", "San%20Diego"))
                .andExpect(queryParam("count", "1"))
                .andRespond(withSuccess("""
                        {"results":[{"id":5391811,"name":"San Diego","latitude":32.71571,"longitude":-117.16472,
                          "country":"United States","admin1":"California","timezone":"America/Los_Angeles"}]}
                        """, MediaType.APPLICATION_JSON));

        Optional<GeoLocation> result = provider.geocode("San Diego");

        assertThat(result).isPresent();
        assertThat(result.get().displayName()).isEqualTo("San Diego, California");
        assertThat(result.get().latitude()).isEqualTo(32.71571);
        assertThat(result.get().longitude()).isEqualTo(-117.16472);
        assertThat(result.get().timezone()).isEqualTo("America/Los_Angeles");
    }

    @Test
    void geocode_withNoResults_isEmpty() {
        server.expect(requestTo(startsWith("https://geocoding-api.open-meteo.com/v1/search")))
                .andRespond(withSuccess("{\"generationtime_ms\":0.5}", MediaType.APPLICATION_JSON));

        assertThat(provider.geocode("zzzz")).isEmpty();
    }

    @Test
    void forecast_asksForFahrenheitAndParsesCurrentAndDaily() {
        server.expect(requestTo(startsWith("https://api.open-meteo.com/v1/forecast")))
                .andExpect(queryParam("latitude", "32.7157"))
                .andExpect(queryParam("longitude", "-117.1647"))
                .andExpect(queryParam("temperature_unit", "fahrenheit"))
                .andExpect(queryParam("timezone", "America%2FLos_Angeles"))
                .andExpect(queryParam("forecast_days", "2"))
                .andRespond(withSuccess("""
                        {"current":{"time":"2026-10-23T11:00","temperature_2m":71.6,"weather_code":2,"is_day":1},
                         "daily":{"time":["2026-10-23","2026-10-24"],"weather_code":[2,63],
                                  "temperature_2m_max":[75.4,68.0],"temperature_2m_min":[60.5,58.0],
                                  "precipitation_probability_max":[10,null]}}
                        """, MediaType.APPLICATION_JSON));

        Forecast forecast = provider.forecast(32.7157, -117.1647, "America/Los_Angeles");

        assertThat(forecast.current().temperature()).isEqualTo(71.6);
        assertThat(forecast.current().weatherCode()).isEqualTo(2);
        assertThat(forecast.current().isDay()).isTrue();
        assertThat(forecast.daily()).hasSize(2);
        assertThat(forecast.daily().get(0).date()).isEqualTo(LocalDate.of(2026, 10, 23));
        assertThat(forecast.daily().get(0).high()).isEqualTo(75.4);
        assertThat(forecast.daily().get(0).precipitationChance()).isEqualTo(10);
        assertThat(forecast.daily().get(1).weatherCode()).isEqualTo(63);
        assertThat(forecast.daily().get(1).precipitationChance()).isNull();
    }
}
