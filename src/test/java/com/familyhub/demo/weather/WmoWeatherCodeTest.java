package com.familyhub.demo.weather;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WmoWeatherCodeTest {
    @Test
    void describesCommonCodes() {
        assertThat(WmoWeatherCode.describe(0)).isEqualTo("Clear sky");
        assertThat(WmoWeatherCode.describe(2)).isEqualTo("Partly cloudy");
        assertThat(WmoWeatherCode.describe(63)).isEqualTo("Rain");
        assertThat(WmoWeatherCode.describe(95)).isEqualTo("Thunderstorm");
    }

    @Test
    void unknownCodeIsNotAnError() {
        assertThat(WmoWeatherCode.describe(42)).isEqualTo("Unknown");
    }
}
