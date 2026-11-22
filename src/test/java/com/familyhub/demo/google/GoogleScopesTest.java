package com.familyhub.demo.google;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Which granted scopes let the hub write events (Phase 4). Accounts signed in before the
 * change hold only the read-only events scope: their calendars show, but cannot be edited
 * until the account signs in again.
 */
class GoogleScopesTest {

    @Test
    void theReadOnlyScopesThatAreLiveTodayCannotWrite() {
        assertThat(GoogleScopes.canWrite("https://www.googleapis.com/auth/calendar.calendarlist.readonly "
                + "https://www.googleapis.com/auth/calendar.events.readonly")).isFalse();
        assertThat(GoogleScopes.canWrite(null)).isFalse();
        assertThat(GoogleScopes.canWrite("")).isFalse();
    }

    @Test
    void theEventsScopeOrTheWholeCalendarScopeCanWrite() {
        assertThat(GoogleScopes.canWrite("https://www.googleapis.com/auth/calendar.calendarlist.readonly "
                + "https://www.googleapis.com/auth/calendar.events")).isTrue();
        assertThat(GoogleScopes.canWrite("https://www.googleapis.com/auth/calendar")).isTrue();
    }

    @Test
    void theHubAsksForTheEventsScopeAndTheReadOnlyCalendarList() {
        assertThat(GoogleScopes.REQUESTED.split(" ")).containsExactlyInAnyOrder(
                "https://www.googleapis.com/auth/calendar.events",
                "https://www.googleapis.com/auth/calendar.calendarlist.readonly");
    }
}
