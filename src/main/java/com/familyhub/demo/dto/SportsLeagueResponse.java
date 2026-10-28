package com.familyhub.demo.dto;

import com.familyhub.demo.sports.SportsLeague;

/**
 * A league the family could follow teams in, whether a data source covers it yet, and
 * whether that source has live scores (without them, games show with final scores only).
 */
public record SportsLeagueResponse(SportsLeague league, String name, boolean available, boolean liveScores) {
}
