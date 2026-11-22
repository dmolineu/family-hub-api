package com.familyhub.demo.event;

import com.familyhub.demo.service.CalendarCacheSync;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** A write's request for a sync syncs that calendar; a failure is logged, not thrown. */
class CalendarSyncRequestListenerTest {

    private final CalendarCacheSync sync = mock(CalendarCacheSync.class);
    private final CalendarSyncRequestListener listener = new CalendarSyncRequestListener(sync);

    @Test
    void theRequestedCalendarIsSynced() {
        UUID calendarId = UUID.randomUUID();

        listener.onRequested(new CalendarSyncRequested(calendarId));

        verify(sync).syncCalendar(calendarId);
    }

    @Test
    void aFailingSyncDoesNotEscape() {
        UUID calendarId = UUID.randomUUID();
        when(sync.syncCalendar(calendarId)).thenThrow(new IllegalStateException("database down"));

        assertThatCode(() -> listener.onRequested(new CalendarSyncRequested(calendarId))).doesNotThrowAnyException();
    }
}
