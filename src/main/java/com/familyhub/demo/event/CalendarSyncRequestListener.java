package com.familyhub.demo.event;

import com.familyhub.demo.service.CalendarCacheSync;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * Syncs a calendar soon after a write to it (Phase 4), after the commit and off the
 * request thread, so changes made in Google meanwhile show up without waiting for the
 * next poll. Off in tests ({@code google.calendar.sync-on-request: false}), where a sync
 * racing the test's own writes would make results depend on timing.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "google.calendar.sync-on-request", havingValue = "true", matchIfMissing = true)
public class CalendarSyncRequestListener {

    private final CalendarCacheSync sync;

    /**
     * Creates the listener.
     *
     * @param sync the calendar cache sync
     */
    public CalendarSyncRequestListener(CalendarCacheSync sync) {
        this.sync = sync;
    }

    /**
     * Syncs the calendar.
     *
     * @param event which calendar
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRequested(CalendarSyncRequested event) {
        try {
            sync.syncCalendar(event.calendarId());
        } catch (RuntimeException e) {
            log.warn("A requested calendar sync failed, calendarId={}: {}", event.calendarId(), e.toString());
        }
    }
}
