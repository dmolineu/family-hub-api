package com.familyhub.demo.repository;

import com.familyhub.demo.model.GoogleCalendar;
import com.familyhub.demo.model.GoogleCalendarAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

/** The Google calendars families can see (Phase 4 cache). */
public interface GoogleCalendarRepository extends JpaRepository<GoogleCalendar, UUID> {

    /**
     * Every account's access to a calendar.
     *
     * @param calendar the cached calendar
     * @return one row per signed-in account that sees it
     */
    @Query("select a from GoogleCalendarAccess a join fetch a.token where a.calendar = :calendar")
    List<GoogleCalendarAccess> findAccess(@Param("calendar") GoogleCalendar calendar);
}
