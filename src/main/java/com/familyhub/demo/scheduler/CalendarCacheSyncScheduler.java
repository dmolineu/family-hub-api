package com.familyhub.demo.scheduler;

import com.familyhub.demo.service.CalendarCacheSync;
import com.familyhub.demo.service.LegacyEventBackfill;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Polls every enabled Google calendar (Phase 4). {@code fixedDelay}, so a slow run never
 * overlaps the next. Only when Google is configured, or faked for the dev profile.
 */
@Component
@ConditionalOnExpression("!'${google.oauth.client-id:}'.isEmpty() or ${google.calendar.fake:false}")
public class CalendarCacheSyncScheduler {

    private final CalendarCacheSync sync;
    private final LegacyEventBackfill backfill;

    /**
     * Creates the poller.
     *
     * @param sync     the calendar cache sync
     * @param backfill copies the families' own events to Google once they may
     */
    public CalendarCacheSyncScheduler(CalendarCacheSync sync, LegacyEventBackfill backfill) {
        this.sync = sync;
        this.backfill = backfill;
    }

    /** Syncs every enabled calendar, then copies any of the families' own events that wait. */
    @Scheduled(fixedDelayString = "${google.calendar.poll-interval:PT60S}",
            initialDelayString = "${google.calendar.poll-initial-delay:PT30S}")
    public void pollCalendars() {
        sync.syncAll();
        backfill.copyAll();
    }
}
