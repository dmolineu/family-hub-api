package com.familyhub.demo.weather;

/** A geocoded place. {@code region} is the state/province and may be null. */
public record GeoLocation(
        String name,
        String region,
        String country,
        double latitude,
        double longitude,
        String timezone
) {
    /** "San Diego, California", or just the name when there is no region. */
    public String displayName() {
        return region == null || region.isBlank() ? name : name + ", " + region;
    }
}
