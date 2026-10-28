package com.familyhub.demo.dto;

import com.familyhub.demo.sports.SportsLeague;

/** A league the family could follow teams in, and whether a data source covers it yet. */
public record SportsLeagueResponse(SportsLeague league, String name, boolean available) {
}
