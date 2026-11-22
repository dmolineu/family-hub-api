package com.familyhub.demo.repository;

import com.familyhub.demo.model.GoogleCalendar;
import com.familyhub.demo.model.GoogleCalendarAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

/** Each signed-in account's role on each calendar (Phase 4). */
public interface GoogleCalendarAccessRepository extends JpaRepository<GoogleCalendarAccess, GoogleCalendarAccess.Key> {

    /**
     * The access rows of several calendars, with their accounts loaded.
     *
     * @param calendars the cached calendars
     * @return every account's role on each of them
     */
    @Query("select a from GoogleCalendarAccess a join fetch a.token where a.calendar in :calendars")
    List<GoogleCalendarAccess> findByCalendars(@Param("calendars") Collection<GoogleCalendar> calendars);

    /**
     * One account's access rows, with their calendars loaded.
     *
     * @param tokenId the signed-in account
     * @return its role on each calendar it sees
     */
    @Query("select a from GoogleCalendarAccess a join fetch a.calendar where a.token.id = :tokenId")
    List<GoogleCalendarAccess> findByTokenId(@Param("tokenId") UUID tokenId);

    /**
     * One calendar's access rows, with their accounts loaded.
     *
     * @param calendarId the cached calendar
     * @return every account's role on it
     */
    @Query("select a from GoogleCalendarAccess a join fetch a.token where a.calendar.id = :calendarId")
    List<GoogleCalendarAccess> findByCalendarId(@Param("calendarId") UUID calendarId);
}
