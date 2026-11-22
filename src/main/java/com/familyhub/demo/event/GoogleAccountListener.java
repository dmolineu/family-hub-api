package com.familyhub.demo.event;

import com.familyhub.demo.model.GoogleCalendar;
import com.familyhub.demo.model.GoogleOAuthToken;
import com.familyhub.demo.repository.GoogleCalendarRepository;
import com.familyhub.demo.repository.GoogleOAuthTokenRepository;
import com.familyhub.demo.service.CalendarCacheSync;
import com.familyhub.demo.service.GoogleCalendarAdminService;
import com.familyhub.demo.service.LegacyEventBackfill;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import java.util.Optional;

/**
 * After a member connects Google (Phase 4): reads the account's calendar list and syncs
 * the family's switched-on calendars, after the sign-in is committed and off the
 * request thread. A reconnect that grants the write scope is then visible within
 * seconds. Failures are logged; the scheduler tries again.
 */
@Slf4j
@Component
public class GoogleAccountListener {

    private final GoogleCalendarAdminService admin;
    private final CalendarCacheSync sync;
    private final GoogleOAuthTokenRepository tokens;
    private final GoogleCalendarRepository calendars;
    private final LegacyEventBackfill backfill;

    /**
     * Creates the listener.
     *
     * @param admin     reads an account's calendar list
     * @param sync      syncs calendars
     * @param tokens    signed-in accounts
     * @param calendars cached calendars
     * @param backfill  copies the family's own events once the account may write
     */
    public GoogleAccountListener(GoogleCalendarAdminService admin, CalendarCacheSync sync,
                                 GoogleOAuthTokenRepository tokens, GoogleCalendarRepository calendars,
                                 LegacyEventBackfill backfill) {
        this.admin = admin;
        this.sync = sync;
        this.tokens = tokens;
        this.calendars = calendars;
        this.backfill = backfill;
    }

    /**
     * Reads the account's calendars and syncs the family's.
     *
     * @param event which account connected
     */
    @Async
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onConnected(GoogleAccountConnected event) {
        try {
            admin.discover(event.tokenId());
            Optional<GoogleOAuthToken> account = tokens.findById(event.tokenId());
            if (account.isEmpty()) {
                return;
            }
            java.util.UUID familyId = account.get().getMember().getFamily().getId();
            // The reconnect that grants the write scope is what lets the family's own events go to Google.
            backfill.copyFamily(familyId);
            for (GoogleCalendar calendar : calendars.findEnabledByFamily(familyId)) {
                sync.syncCalendar(calendar.getId());
            }
        } catch (RuntimeException e) {
            log.warn("Could not read or sync the calendars of a newly connected account, tokenId={}: {}",
                    event.tokenId(), e.toString());
        }
    }
}
