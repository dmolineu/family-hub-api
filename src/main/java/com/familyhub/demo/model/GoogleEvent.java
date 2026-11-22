package com.familyhub.demo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * One cached Google event (Phase 4): a single event, a recurring master, or an edited or
 * cancelled instance of one, stored as Google returns it. Timed events use
 * {@link #startAt}/{@link #endAt}; all-day events {@link #startDate}/{@link #endDate},
 * where the end is Google's exclusive date. {@link #recurringEventId} is the master's
 * Google id as a string, because instances can arrive before their master.
 * {@link #raw} is Google's whole event as JSON, sent back on update so fields the app
 * does not model survive.
 */
@Entity
@Getter
@Setter
@Table(name = "google_event")
public class GoogleEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "calendar_id", nullable = false)
    private GoogleCalendar calendar;

    @Column(name = "google_event_id", nullable = false, length = 1024)
    private String googleEventId;

    @Column(name = "ical_uid", length = 1024)
    private String icalUid;

    @Column(length = 255)
    private String etag;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GoogleEventStatus status = GoogleEventStatus.CONFIRMED;

    @Column(columnDefinition = "TEXT")
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String location;

    @Column(name = "html_link", length = 2048)
    private String htmlLink;

    @Column(name = "all_day", nullable = false)
    private boolean allDay;

    @Column(name = "start_at")
    private Instant startAt;

    @Column(name = "end_at")
    private Instant endAt;

    @Column(name = "start_date")
    private LocalDate startDate;

    @Column(name = "end_date")
    private LocalDate endDate;

    @Column(name = "time_zone", length = 100)
    private String timeZone;

    /** RRULE/EXDATE/RDATE lines exactly as Google sent them; null for a non-recurring event. */
    @Column(name = "recurrence")
    private String[] recurrence;

    @Column(name = "recurring_event_id", length = 1024)
    private String recurringEventId;

    @Column(name = "original_start_at")
    private Instant originalStartAt;

    @Column(name = "original_start_date")
    private LocalDate originalStartDate;

    @Column(name = "organizer_email", length = 320)
    private String organizerEmail;

    @Column(name = "organizer_self", nullable = false)
    private boolean organizerSelf;

    /** The members the event belongs to, once attributed; null until then. */
    @Column(name = "member_ids")
    private UUID[] memberIds;

    @Column(name = "google_updated_at")
    private Instant googleUpdatedAt;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String raw;
}
