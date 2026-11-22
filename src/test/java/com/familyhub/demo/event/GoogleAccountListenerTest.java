package com.familyhub.demo.event;

import com.familyhub.demo.google.GoogleCalendarException;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.GoogleCalendar;
import com.familyhub.demo.model.GoogleOAuthToken;
import com.familyhub.demo.repository.GoogleCalendarRepository;
import com.familyhub.demo.repository.GoogleOAuthTokenRepository;
import com.familyhub.demo.service.CalendarCacheSync;
import com.familyhub.demo.service.GoogleCalendarAdminService;
import com.familyhub.demo.service.LegacyEventBackfill;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * After a member connects Google, the hub reads that account's calendar list, copies the
 * family's own events to Google if it now may, and syncs the family's switched-on calendars, so a reconnect that grants the write scope shows
 * up within seconds instead of at the next poll. A Google failure is logged, not thrown:
 * the scheduler tries again.
 */
class GoogleAccountListenerTest {

    private final GoogleCalendarAdminService admin = mock(GoogleCalendarAdminService.class);
    private final CalendarCacheSync sync = mock(CalendarCacheSync.class);
    private final GoogleOAuthTokenRepository tokens = mock(GoogleOAuthTokenRepository.class);
    private final GoogleCalendarRepository calendars = mock(GoogleCalendarRepository.class);
    private final LegacyEventBackfill backfill = mock(LegacyEventBackfill.class);
    private final GoogleAccountListener listener = new GoogleAccountListener(admin, sync, tokens, calendars, backfill);

    private final UUID tokenId = UUID.randomUUID();
    private final UUID familyId = UUID.randomUUID();
    private final GoogleCalendar hub = new GoogleCalendar();
    private final GoogleCalendar school = new GoogleCalendar();

    @BeforeEach
    void setUp() {
        Family family = new Family();
        family.setId(familyId);
        FamilyMember dad = new FamilyMember();
        dad.setFamily(family);
        GoogleOAuthToken token = new GoogleOAuthToken();
        token.setId(tokenId);
        token.setMember(dad);
        when(tokens.findById(tokenId)).thenReturn(Optional.of(token));
        hub.setId(UUID.randomUUID());
        school.setId(UUID.randomUUID());
        when(calendars.findEnabledByFamily(familyId)).thenReturn(List.of(hub, school));
    }

    @Test
    void theAccountsCalendarsAreReadAndTheFamilysCalendarsSynced() {
        listener.onConnected(new GoogleAccountConnected(tokenId));

        // Reading the calendar list first is what finds the write access the copy needs.
        org.mockito.InOrder order = org.mockito.Mockito.inOrder(admin, backfill, sync);
        order.verify(admin).discover(tokenId);
        order.verify(backfill).copyFamily(familyId);
        order.verify(sync).syncCalendar(hub.getId());
        verify(sync).syncCalendar(school.getId());
    }

    @Test
    void aGoogleFailureIsLoggedAndNothingIsSynced() {
        doThrow(new GoogleCalendarException(GoogleCalendarException.Kind.UNAVAILABLE, "down", null, null))
                .when(admin).discover(tokenId);

        listener.onConnected(new GoogleAccountConnected(tokenId));

        verify(sync, never()).syncCalendar(any());
    }
}
