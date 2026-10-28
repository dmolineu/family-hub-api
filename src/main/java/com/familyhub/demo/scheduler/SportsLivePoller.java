package com.familyhub.demo.scheduler;

import com.familyhub.demo.service.SportsSyncService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Keeps followed teams' games current: live scores every 30 seconds while a game is
 * on (nothing at all otherwise), a retry for teams never synced, and a daily pass that
 * fills in TBD times and schedule changes. Timings come from {@code sports.*}.
 */
@Component
@RequiredArgsConstructor
public class SportsLivePoller {
    private final SportsSyncService syncService;

    /** Live scores for games in their live window. */
    @Scheduled(fixedDelayString = "${sports.live-poll-interval:PT30S}",
            initialDelayString = "${sports.poll-initial-delay:PT30S}")
    public void refresh() {
        syncService.refreshLive();
    }

    /** Teams whose schedule has never been read. */
    @Scheduled(fixedDelayString = "${sports.unsynced-retry-interval:PT5M}",
            initialDelayString = "${sports.poll-initial-delay:PT30S}")
    public void syncUnsyncedTeams() {
        syncService.syncUnsynced();
    }

    /** Every followed team's schedule, once a day. */
    @Scheduled(cron = "${sports.schedule-cron:0 30 4 * * *}")
    public void syncAll() {
        syncService.syncAll();
    }
}
