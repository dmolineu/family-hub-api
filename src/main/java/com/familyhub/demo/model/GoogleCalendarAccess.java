package com.familyhub.demo.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.MapsId;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.io.Serializable;
import java.util.UUID;

/**
 * What one signed-in account may do on one calendar, from Google's calendarList. Writes
 * use an owner's token, else a writer's.
 */
@Entity
@Getter
@Setter
@NoArgsConstructor
@Table(name = "google_calendar_access")
public class GoogleCalendarAccess {

    /** The (calendar, token) pair that identifies a row. */
    @Embeddable
    @Getter
    @Setter
    @NoArgsConstructor
    @AllArgsConstructor
    @EqualsAndHashCode
    public static class Key implements Serializable {
        private UUID calendarId;
        private UUID tokenId;
    }

    @EmbeddedId
    private Key id = new Key();

    @MapsId("calendarId")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "calendar_id")
    private GoogleCalendar calendar;

    @MapsId("tokenId")
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "token_id")
    private GoogleOAuthToken token;

    @Enumerated(EnumType.STRING)
    @Column(name = "access_role", nullable = false, length = 20)
    private GoogleAccessRole accessRole;

    /**
     * Records an account's access to a calendar.
     *
     * @param calendar   the cached calendar
     * @param token      the signed-in account
     * @param accessRole what Google says that account may do there
     */
    public GoogleCalendarAccess(GoogleCalendar calendar, GoogleOAuthToken token, GoogleAccessRole accessRole) {
        this.id = new Key(calendar.getId(), token.getId());
        this.calendar = calendar;
        this.token = token;
        this.accessRole = accessRole;
    }
}
