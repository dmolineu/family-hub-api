package com.familyhub.demo.repository;

import com.familyhub.demo.model.GoogleCalendar;
import com.familyhub.demo.model.GoogleEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** The Google event cache (Phase 4). */
public interface GoogleEventRepository extends JpaRepository<GoogleEvent, UUID> {

    /**
     * Single (non-recurring, non-instance), non-cancelled events overlapping a half-open
     * window.
     *
     * @param calendars the calendars to read
     * @param from      timed events must end after this
     * @param to        timed events must start before this
     * @param fromDate  all-day events must end (exclusively) after this date
     * @param toDate    all-day events must start before this date
     * @return the matching events
     */
    @Query("select e from GoogleEvent e where e.calendar in :calendars "
            + "and e.recurrence is null and e.recurringEventId is null "
            + "and e.status <> com.familyhub.demo.model.GoogleEventStatus.CANCELLED "
            + "and ((e.allDay = false and e.startAt < :to and e.endAt > :from) "
            + "or (e.allDay = true and e.startDate < :toDate and e.endDate > :fromDate))")
    List<GoogleEvent> findSinglesInWindow(@Param("calendars") Collection<GoogleCalendar> calendars,
                                          @Param("from") Instant from, @Param("to") Instant to,
                                          @Param("fromDate") LocalDate fromDate,
                                          @Param("toDate") LocalDate toDate);

    /**
     * Recurring masters that are not cancelled, whatever their dates: whether a series
     * reaches a window is only known once its rule is expanded.
     *
     * @param calendars the calendars to read
     * @return the masters
     */
    @Query("select e from GoogleEvent e where e.calendar in :calendars "
            + "and e.recurrence is not null and e.recurringEventId is null "
            + "and e.status <> com.familyhub.demo.model.GoogleEventStatus.CANCELLED")
    List<GoogleEvent> findMasters(@Param("calendars") Collection<GoogleCalendar> calendars);

    /**
     * Edited and cancelled instances of the given masters, cancelled ones included,
     * because those are what hide an occurrence.
     *
     * @param calendars         the calendars to read
     * @param recurringEventIds the masters' Google ids
     * @return the instances; pair them with their master by calendar and id
     */
    @Query("select e from GoogleEvent e where e.calendar in :calendars "
            + "and e.recurringEventId in :ids")
    List<GoogleEvent> findInstancesOf(@Param("calendars") Collection<GoogleCalendar> calendars,
                                      @Param("ids") Collection<String> recurringEventIds);

    /**
     * One cached event by Google's id on one calendar.
     *
     * @param calendar      the cached calendar
     * @param googleEventId Google's event id
     * @return the event, if cached
     */
    Optional<GoogleEvent> findByCalendarAndGoogleEventId(GoogleCalendar calendar, String googleEventId);

    /**
     * Every cached row of one calendar, cancelled occurrences included.
     *
     * @param calendarId the cached calendar's id
     * @return its rows
     */
    @Query("select e from GoogleEvent e where e.calendar.id = :calendarId")
    List<GoogleEvent> findAllByCalendarId(@Param("calendarId") UUID calendarId);

    /**
     * Drops a calendar's cached events, when it is switched off.
     *
     * @param calendarId the cached calendar's id
     * @return how many rows went
     */
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query("delete from GoogleEvent e where e.calendar.id = :calendarId")
    int deleteAllByCalendarId(@Param("calendarId") UUID calendarId);
}
