package com.familyhub.demo.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

/** Sports data sources, how far ahead schedules are kept and how often live scores refresh. */
@ConfigurationProperties(prefix = "sports")
@Configuration
@Data
public class SportsProperties {
    /** MLB's official Stats API; its terms allow individual, non-commercial use. No key is needed. */
    private String mlbBaseUrl = "https://statsapi.mlb.com";
    /** Team logos, shown from MLB's image host by each device; {id} is the team id. */
    private String mlbLogoUrl = "https://www.mlbstatic.com/team-logos/{id}.svg";
    private Duration connectTimeout = Duration.ofSeconds(3);
    private Duration readTimeout = Duration.ofSeconds(10);
    /** Schedules are kept from this many days back to {@link #scheduleDaysAhead} ahead. */
    private int scheduleDaysBehind = 7;
    private int scheduleDaysAhead = 365;
    /** A game counts as live from this long before its start... */
    private Duration liveWindowBefore = Duration.ofMinutes(15);
    /** ...until it is final, or this long after its start at the most. */
    private Duration liveWindowAfter = Duration.ofHours(6);
    /** Team lists change once a season; they are fetched again after this long. */
    private Duration teamListTtl = Duration.ofHours(24);
    /** A summary is fetched again after this long, and only while a panel asks for it. */
    private Duration summaryTtl = Duration.ofSeconds(60);
}
