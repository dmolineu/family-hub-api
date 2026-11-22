package com.familyhub.demo.model;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.OnDelete;
import org.hibernate.annotations.OnDeleteAction;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

/**
 * One of the hub's own events from before Phase 4 (inbox events among them). Since Phase 4
 * Google holds the family's events; these rows wait here until they are copied to the
 * family's default Google calendar ({@code copied_*}), and the table goes in a later
 * release (V39). Nothing new is written here.
 */
@Entity
@Getter
@Setter
public class CalendarEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    // TEXT: Google allows far longer titles, locations and rules than 255 (V26).
    @Column(nullable = false, columnDefinition = "TEXT")
    private String title;

    @Column(nullable = false)
    private LocalTime startTime;

    @Column(nullable = false)
    private LocalTime endTime;

    @Column(nullable = false)
    private LocalDate date;

    @ManyToOne
    @JoinColumn(name = "member_id", nullable = false)
    private FamilyMember member;

    @ManyToOne
    @JoinColumn(name = "family_id", nullable = false)
    private Family family;

    private LocalDate endDate;

    private boolean isAllDay;

    @Column(columnDefinition = "TEXT")
    private String location;

    @Column(columnDefinition = "TEXT")
    private String recurrenceRule;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "recurring_event_id")
    @OnDelete(action = OnDeleteAction.CASCADE)
    private CalendarEvent recurringEvent;

    private LocalDate originalDate;

    @Column(nullable = false)
    private boolean isCancelled;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private EventSource source = EventSource.NATIVE;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String exdates;

    /** Phase 4: the Google calendar this event was copied to, once copied. */
    @Column(name = "copied_calendar_id")
    private UUID copiedCalendarId;

    /** Phase 4: Google's id of the copy. */
    @Column(name = "copied_google_event_id", length = 1024)
    private String copiedGoogleEventId;

    /** Phase 4: when it was copied; null while it waits for an account that may write. */
    @Column(name = "copied_at")
    private java.time.Instant copiedAt;
}
