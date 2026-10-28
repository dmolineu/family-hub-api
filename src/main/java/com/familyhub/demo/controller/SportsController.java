package com.familyhub.demo.controller;

import com.familyhub.demo.dto.ApiResponse;
import com.familyhub.demo.dto.FollowTeamRequest;
import com.familyhub.demo.dto.FollowedTeamResponse;
import com.familyhub.demo.dto.FollowedTeamUpdateRequest;
import com.familyhub.demo.dto.SportsGameResponse;
import com.familyhub.demo.dto.SportsLeagueResponse;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.service.SportsGameService;
import com.familyhub.demo.service.SportsSyncService;
import com.familyhub.demo.service.SportsTeamService;
import com.familyhub.demo.sports.GameSummary;
import com.familyhub.demo.sports.ProviderTeam;
import com.familyhub.demo.sports.SportsLeague;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/** Teams the family follows, their games, live scores and the live panel. */
@RestController
@RequestMapping("/api/sports")
@RequiredArgsConstructor
public class SportsController {
    private final SportsTeamService teamService;
    private final SportsSyncService syncService;
    private final SportsGameService gameService;

    /** Every league and whether a data source covers it yet. */
    @GetMapping("/leagues")
    public ResponseEntity<ApiResponse<List<SportsLeagueResponse>>> leagues() {
        return ResponseEntity.ok(new ApiResponse<>(teamService.leagues(), ""));
    }

    /** A league's teams matching a search. */
    @GetMapping("/leagues/{league}/teams")
    public ResponseEntity<ApiResponse<List<ProviderTeam>>> teams(
            @PathVariable SportsLeague league,
            @RequestParam(required = false) String query
    ) {
        return ResponseEntity.ok(new ApiResponse<>(teamService.searchTeams(league, query), ""));
    }

    /** The family's followed teams. */
    @GetMapping("/teams")
    public ResponseEntity<ApiResponse<List<FollowedTeamResponse>>> followed(@AuthenticationPrincipal Family family) {
        return ResponseEntity.ok(new ApiResponse<>(teamService.list(family), ""));
    }

    /** Follows a team and reads its schedule straight away (later passes retry if the source is down). */
    @PostMapping("/teams")
    public ResponseEntity<ApiResponse<FollowedTeamResponse>> follow(
            @Valid @RequestBody FollowTeamRequest request,
            @AuthenticationPrincipal Family family
    ) {
        FollowedTeamResponse team = teamService.follow(family, request);
        syncService.syncTeamQuietly(team.id());
        return ResponseEntity.status(HttpStatus.CREATED).body(new ApiResponse<>(team, "Team followed"));
    }

    /** Changes a followed team's fans, colour, calendar switch or hidden scores. */
    @PatchMapping("/teams/{id}")
    public ResponseEntity<ApiResponse<FollowedTeamResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody FollowedTeamUpdateRequest request,
            @AuthenticationPrincipal Family family
    ) {
        return ResponseEntity.ok(new ApiResponse<>(teamService.update(family, id, request), "Team saved"));
    }

    /** Unfollows a team. */
    @DeleteMapping("/teams/{id}")
    public ResponseEntity<Void> unfollow(@PathVariable UUID id, @AuthenticationPrincipal Family family) {
        teamService.unfollow(family, id);
        return ResponseEntity.noContent().build();
    }

    /** Games on the given days (family time zone), for the calendar. */
    @GetMapping("/games")
    public ResponseEntity<ApiResponse<List<SportsGameResponse>>> games(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @AuthenticationPrincipal Family family
    ) {
        return ResponseEntity.ok(new ApiResponse<>(gameService.games(family, from, to), ""));
    }

    /** Games live now or about to start, for the live chip. */
    @GetMapping("/live")
    public ResponseEntity<ApiResponse<List<SportsGameResponse>>> live(@AuthenticationPrincipal Family family) {
        return ResponseEntity.ok(new ApiResponse<>(gameService.live(family), ""));
    }

    /** The live panel's details for one game. */
    @GetMapping("/games/{id}/summary")
    public ResponseEntity<ApiResponse<GameSummary>> summary(
            @PathVariable UUID id,
            @AuthenticationPrincipal Family family
    ) {
        return ResponseEntity.ok(new ApiResponse<>(gameService.summary(family, id), ""));
    }
}
