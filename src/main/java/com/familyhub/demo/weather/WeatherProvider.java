package com.familyhub.demo.weather;

import java.util.Optional;

/** The one seam to the outside weather service; tests swap in a fake. */
public interface WeatherProvider {
    /** Resolves free text such as "San Diego" or "92101" to a place, empty when unknown. */
    Optional<GeoLocation> geocode(String query);

    /** Current conditions plus the next few days for a point, in the given IANA zone. */
    Forecast forecast(double latitude, double longitude, String timezone);
}
