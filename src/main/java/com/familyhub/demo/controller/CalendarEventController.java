package com.familyhub.demo.controller;

import com.familyhub.demo.dto.ApiResponse;
import com.familyhub.demo.dto.calendar.CalendarEventResponse;
import com.familyhub.demo.dto.calendar.CalendarWriteRequest;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.service.CalendarQueryService;
import com.familyhub.demo.service.CalendarWriteService;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * The family calendar (Phase 4): read from the cache of Google, written through Google.
 * A date of a series is named by the series' id and its original start in Google's
 * compact form ({@code 20261114T000000Z}, or {@code 20261114} for an all-day series).
 */
@RestController
@RequestMapping("/api/calendar/events")
public class CalendarEventController {

    private final CalendarQueryService query;
    private final CalendarWriteService writes;

    /**
     * Creates the controller.
     *
     * @param query  reads the calendar
     * @param writes writes to Google
     */
    public CalendarEventController(CalendarQueryService query, CalendarWriteService writes) {
        this.query = query;
        this.writes = writes;
    }

    /**
     * The events and occurrences between two days, inclusive, in the family's zone.
     *
     * @param family    the signed-in family
     * @param startDate the first day
     * @param endDate   the last day
     * @param memberId  only this member's, or every member's when absent
     * @return the events, by start
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<CalendarEventResponse>>> events(
            @AuthenticationPrincipal Family family,
            @RequestParam LocalDate startDate,
            @RequestParam LocalDate endDate,
            @RequestParam(required = false) UUID memberId) {
        return ResponseEntity.ok(new ApiResponse<>(query.eventsFor(family, startDate, endDate, memberId), ""));
    }

    /**
     * One event: a single event, a series as a whole, or a changed date of one.
     *
     * @param family the signed-in family
     * @param id     the event's id
     * @return the event
     */
    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<CalendarEventResponse>> event(@AuthenticationPrincipal Family family,
                                                                    @PathVariable UUID id) {
        return ResponseEntity.ok(new ApiResponse<>(query.eventById(family, id), ""));
    }

    /**
     * Creates an event, on the family's default calendar unless the request names one.
     *
     * @param family  the signed-in family
     * @param request the event
     * @return the created event
     */
    @PostMapping
    public ResponseEntity<ApiResponse<CalendarEventResponse>> create(
            @AuthenticationPrincipal Family family, @Valid @RequestBody CalendarWriteRequest request) {
        CalendarEventResponse created = writes.create(family, request);
        return ResponseEntity.created(URI.create("/api/calendar/events/" + created.id()))
                .body(new ApiResponse<>(created, "Event created successfully"));
    }

    /**
     * Changes an event, or a whole series.
     *
     * @param family  the signed-in family
     * @param id      the event's id
     * @param request its new state, with the etag it was read with
     * @return the changed event
     */
    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<CalendarEventResponse>> update(
            @AuthenticationPrincipal Family family, @PathVariable UUID id,
            @Valid @RequestBody CalendarWriteRequest request) {
        return ResponseEntity.ok(new ApiResponse<>(writes.update(family, id, request), "Event updated successfully"));
    }

    /**
     * Deletes an event; for a series, every date of it.
     *
     * @param family the signed-in family
     * @param id     the event's id
     * @param etag   the etag it was read with; absent deletes whatever Google holds
     * @return no content
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@AuthenticationPrincipal Family family, @PathVariable UUID id,
                                       @RequestParam(required = false) String etag) {
        writes.delete(family, id, etag);
        return ResponseEntity.noContent().build();
    }

    /**
     * Changes one date of a series on its own.
     *
     * @param family        the signed-in family
     * @param id            the series' id
     * @param originalStart the date's original start, compact
     * @param request       the date's new state
     * @return the changed date
     */
    @PutMapping("/{id}/instances/{originalStart}")
    public ResponseEntity<ApiResponse<CalendarEventResponse>> updateOccurrence(
            @AuthenticationPrincipal Family family, @PathVariable UUID id, @PathVariable String originalStart,
            @Valid @RequestBody CalendarWriteRequest request) {
        return ResponseEntity.ok(new ApiResponse<>(writes.updateOccurrence(family, id, originalStart, request),
                "Instance updated successfully"));
    }

    /**
     * Cancels one date of a series on its own.
     *
     * @param family        the signed-in family
     * @param id            the series' id
     * @param originalStart the date's original start, compact
     * @return no content
     */
    @DeleteMapping("/{id}/instances/{originalStart}")
    public ResponseEntity<Void> deleteOccurrence(@AuthenticationPrincipal Family family, @PathVariable UUID id,
                                                 @PathVariable String originalStart) {
        writes.deleteOccurrence(family, id, originalStart);
        return ResponseEntity.noContent().build();
    }

    /**
     * Changes a date of a series and every date after it.
     *
     * @param family        the signed-in family
     * @param id            the series' id
     * @param originalStart the first changed date's original start, compact
     * @param request       the new state from that date on
     * @return the new series
     */
    @PutMapping("/{id}/instances/{originalStart}/following")
    public ResponseEntity<ApiResponse<CalendarEventResponse>> updateFollowing(
            @AuthenticationPrincipal Family family, @PathVariable UUID id, @PathVariable String originalStart,
            @Valid @RequestBody CalendarWriteRequest request) {
        return ResponseEntity.ok(new ApiResponse<>(writes.updateFollowing(family, id, originalStart, request),
                "Series updated from this date on"));
    }

    /**
     * Deletes a date of a series and every date after it.
     *
     * @param family        the signed-in family
     * @param id            the series' id
     * @param originalStart the first deleted date's original start, compact
     * @return no content
     */
    @DeleteMapping("/{id}/instances/{originalStart}/following")
    public ResponseEntity<Void> deleteFollowing(@AuthenticationPrincipal Family family, @PathVariable UUID id,
                                                @PathVariable String originalStart) {
        writes.deleteFollowing(family, id, originalStart);
        return ResponseEntity.noContent().build();
    }
}
