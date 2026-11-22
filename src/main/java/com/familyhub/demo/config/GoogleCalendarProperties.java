package com.familyhub.demo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/**
 * How the hub talks to Google Calendar (Phase 4): call timeouts, how often calendars
 * are polled, how far back a full sync reaches, and whether the in-memory fake stands
 * in for Google (the dev profile only, so the e2e suite can create events).
 */
@ConfigurationProperties(prefix = "google.calendar")
@Configuration
@Data
public class GoogleCalendarProperties {
    /** Connecting to Google gives up after this. */
    private Duration connectTimeout = Duration.ofSeconds(5);
    /** Waiting for Google's answer gives up after this. */
    private Duration readTimeout = Duration.ofSeconds(15);
    /** The pause between the end of one poll of every calendar and the start of the next. */
    private Duration pollInterval = Duration.ofSeconds(60);
    /** A full sync fetches events that end after now minus this; series always come. */
    private Duration fullSyncLookback = Duration.ofDays(365);
    /** Use the in-memory fake instead of Google. Never in production. */
    private boolean fake;
}
