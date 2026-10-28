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
    /** nflverse's open NFL data (CC BY 4.0): every game's kickoff and final score. No key is needed. */
    private String nflverseGamesUrl = "https://github.com/nflverse/nflverse-data/releases/download/schedules/games.csv";
    private String nflverseTeamsUrl =
            "https://github.com/nflverse/nflverse-data/releases/download/teams/teams_colors_logos.csv";
    /** nflverse's files are read again after this long; final scores reach them within about a day. */
    private Duration nflverseCacheTtl = Duration.ofHours(1);
    /**
     * CollegeFootballData and its basketball twin: college games and final scores. One free
     * key (requested by email) covers both; blank leaves both leagues without a source.
     */
    private String cfbdBaseUrl = "https://api.collegefootballdata.com";
    private String cbbdBaseUrl = "https://api.collegebasketballdata.com";
    private String cfbdApiKey = "";
    /** BigBalls: live NFL and college football scores. Blank means those games have no live scores. */
    private String bigballsBaseUrl = "https://api.bigballsdata.com";
    private String bigballsApiKey = "";
    /** Its free plan allows 250 requests a day, so live games are refreshed this often, not every poll. */
    private Duration bigballsRefreshInterval = Duration.ofMinutes(3);
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
