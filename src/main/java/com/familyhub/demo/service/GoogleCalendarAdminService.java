package com.familyhub.demo.service;

import com.familyhub.demo.dto.calendar.GoogleCalendarSettingResponse;
import com.familyhub.demo.dto.calendar.GoogleCalendarSettingsResponse;
import com.familyhub.demo.dto.calendar.GoogleHealthResponse;
import com.familyhub.demo.dto.calendar.UpdateGoogleCalendarSettingsRequest;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.google.GoogleCalendarGateway;
import com.familyhub.demo.google.GoogleScopes;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.GoogleAccessRole;
import com.familyhub.demo.model.GoogleCalendar;
import com.familyhub.demo.model.GoogleCalendarAccess;
import com.familyhub.demo.model.GoogleOAuthToken;
import com.familyhub.demo.repository.FamilyMemberRepository;
import com.familyhub.demo.repository.FamilyRepository;
import com.familyhub.demo.repository.GoogleCalendarAccessRepository;
import com.familyhub.demo.repository.GoogleCalendarRepository;
import com.familyhub.demo.repository.GoogleEventRepository;
import com.familyhub.demo.repository.GoogleOAuthTokenRepository;
import com.google.api.services.calendar.model.CalendarListEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Family Settings' Google calendars (Phase 4). Reads what each signed-in account can see
 * from Google's calendar list and caches each calendar once per family; new calendars
 * start switched off. A parent switches calendars on and off, says whose lanes their
 * unmarked events go in, and picks the default calendar new events go on. A calendar no
 * account can read any more is switched off, so it never sits unsynced.
 */
@Slf4j
@Service
public class GoogleCalendarAdminService {

    private final GoogleCalendarGateway gateway;
    private final GoogleCalendarRepository calendars;
    private final GoogleCalendarAccessRepository access;
    private final GoogleEventRepository events;
    private final GoogleOAuthTokenRepository tokens;
    private final FamilyMemberRepository members;
    private final FamilyRepository families;
    private final TransactionTemplate transactions;

    /**
     * Creates the service.
     *
     * @param gateway            Google
     * @param calendars          cached calendars
     * @param access             accounts' roles on them
     * @param events             cached events
     * @param tokens             signed-in accounts
     * @param members            family members, for lanes
     * @param families           families, for the default calendar
     * @param transactionManager runs each change in one transaction
     */
    public GoogleCalendarAdminService(GoogleCalendarGateway gateway, GoogleCalendarRepository calendars,
                                      GoogleCalendarAccessRepository access, GoogleEventRepository events,
                                      GoogleOAuthTokenRepository tokens, FamilyMemberRepository members,
                                      FamilyRepository families, PlatformTransactionManager transactionManager) {
        this.gateway = gateway;
        this.calendars = calendars;
        this.access = access;
        this.events = events;
        this.tokens = tokens;
        this.members = members;
        this.families = families;
        this.transactions = new TransactionTemplate(transactionManager);
    }

    /**
     * Reads an account's calendar list from Google and brings the family's calendars and
     * that account's access in line with it. Google is called outside any transaction.
     *
     * @param tokenId the signed-in account
     */
    public void discover(UUID tokenId) {
        GoogleOAuthToken account = tokens.findById(tokenId)
                .orElseThrow(() -> new ResourceNotFoundException("Google account", tokenId));
        List<CalendarListEntry> entries = gateway.listCalendars(account);
        transactions.executeWithoutResult(status -> apply(tokenId, entries));
        log.info("Read the Google calendar list, tokenId={}, calendars={}", tokenId, entries.size());
    }

    private void apply(UUID tokenId, List<CalendarListEntry> entries) {
        GoogleOAuthToken account = tokens.findById(tokenId).orElseThrow();
        Family family = account.getMember().getFamily();
        Map<String, GoogleCalendar> known = new HashMap<>();
        calendars.findAllByFamilyId(family.getId()).forEach(c -> known.put(c.getGoogleCalendarId(), c));
        Set<UUID> seen = new HashSet<>();
        for (CalendarListEntry entry : entries) {
            GoogleCalendar calendar = known.get(entry.getId());
            if (calendar == null) {
                calendar = new GoogleCalendar();
                calendar.setFamily(family);
                calendar.setGoogleCalendarId(entry.getId());
                calendar.setEnabled(false);
            }
            calendar.setSummary(entry.getSummaryOverride() != null ? entry.getSummaryOverride() : entry.getSummary());
            calendar.setColor(entry.getBackgroundColor());
            calendar.setTimeZone(entry.getTimeZone());
            if (calendar.getPollingToken() == null) {
                calendar.setPollingToken(account);
            }
            calendar = calendars.save(calendar);
            seen.add(calendar.getId());
            GoogleAccessRole role = GoogleAccessRole.fromGoogle(entry.getAccessRole());
            GoogleCalendarAccess.Key key = new GoogleCalendarAccess.Key(calendar.getId(), tokenId);
            Optional<GoogleCalendarAccess> existing = access.findById(key);
            if (existing.isPresent()) {
                existing.get().setAccessRole(role);
            } else {
                access.save(new GoogleCalendarAccess(calendar, account, role));
            }
            if (Boolean.TRUE.equals(entry.getPrimary())) {
                account.setGoogleAccountEmail(entry.getId());
            }
        }
        for (GoogleCalendarAccess row : access.findByTokenId(tokenId)) {
            if (!seen.contains(row.getCalendar().getId())) {
                access.delete(row);
                access.flush();
                repick(row.getCalendar(), tokenId);
            }
        }
    }

    /**
     * Before an account is removed: its calendars are polled by another account that sees
     * them, or switched off.
     *
     * @param tokenId the account that is going
     */
    public void accountRemoved(UUID tokenId) {
        transactions.executeWithoutResult(status -> {
            for (GoogleCalendar calendar : calendars.findPolledBy(tokenId)) {
                repick(calendar, tokenId);
            }
        });
    }

    // Another readable account polls the calendar, or it is switched off.
    private void repick(GoogleCalendar calendar, UUID goneTokenId) {
        if (calendar.getPollingToken() != null && !calendar.getPollingToken().getId().equals(goneTokenId)) {
            return;
        }
        Optional<GoogleOAuthToken> other = access.findByCalendarId(calendar.getId()).stream()
                .map(GoogleCalendarAccess::getToken)
                .filter(token -> !token.getId().equals(goneTokenId) && !token.isNeedsReauth())
                .findFirst();
        if (other.isPresent()) {
            calendar.setPollingToken(other.get());
            return;
        }
        calendar.setPollingToken(null);
        if (calendar.isEnabled()) {
            log.info("No signed-in account sees the calendar any more, switching it off, calendarId={}",
                    calendar.getId());
            switchOff(calendar);
        }
    }

    private void switchOff(GoogleCalendar calendar) {
        calendar.setEnabled(false);
        calendar.setSyncToken(null);
        calendar.setFullSyncRequired(true);
        events.deleteAllByCalendarId(calendar.getId());
        families.findById(calendar.getFamily().getId())
                .filter(family -> calendar.getId().equals(family.getDefaultGoogleCalendarId()))
                .ifPresent(family -> family.setDefaultGoogleCalendarId(null));
    }

    /**
     * The family's calendars as Family Settings lists them.
     *
     * @param family the signed-in family
     * @return every calendar any account can see, and the default
     */
    public GoogleCalendarSettingsResponse settings(Family family) {
        return transactions.execute(status -> read(family.getId()));
    }

    private GoogleCalendarSettingsResponse read(UUID familyId) {
        List<GoogleCalendar> all = calendars.findAllByFamilyId(familyId);
        Map<UUID, List<GoogleCalendarAccess>> rolesByCalendar = new HashMap<>();
        if (!all.isEmpty()) {
            for (GoogleCalendarAccess row : access.findByCalendars(all)) {
                rolesByCalendar.computeIfAbsent(row.getCalendar().getId(), id -> new java.util.ArrayList<>()).add(row);
            }
        }
        UUID defaultId = families.findById(familyId).map(Family::getDefaultGoogleCalendarId).orElse(null);
        List<GoogleCalendarSettingResponse> rows = all.stream().map(calendar -> {
            List<GoogleCalendarAccess> roles = rolesByCalendar.getOrDefault(calendar.getId(), List.of());
            GoogleAccessRole best = roles.stream().map(GoogleCalendarAccess::getAccessRole)
                    .min(Comparator.naturalOrder()).orElse(null);
            boolean writable = roles.stream().anyMatch(GoogleCalendarAdminService::canWrite);
            List<UUID> lanes = calendar.getLaneMembers().stream().map(FamilyMember::getId).sorted().toList();
            return new GoogleCalendarSettingResponse(calendar.getId(), calendar.getGoogleCalendarId(),
                    calendar.getSummary(), calendar.getColor(), calendar.getTimeZone(), calendar.isEnabled(),
                    best == null ? null : googleSpelling(best), writable, lanes,
                    calendar.getId().equals(defaultId), calendar.getLastSyncedAt(), calendar.getLastSyncError());
        }).toList();
        return new GoogleCalendarSettingsResponse(rows, defaultId);
    }

    /**
     * Whether a role row lets the hub write: an owner or writer whose sign-in has the write
     * scope and has not been refused.
     *
     * @param row an account's role on a calendar
     * @return true if events can be written through it
     */
    static boolean canWrite(GoogleCalendarAccess row) {
        return row.getAccessRole().canWrite() && GoogleScopes.canWrite(row.getToken().getScope())
                && !row.getToken().isNeedsReauth();
    }

    /**
     * Applies a parent's changes.
     *
     * @param family  the signed-in family
     * @param request the calendars to change and the default
     * @return the family's calendars after the change
     * @throws ResourceNotFoundException if a calendar or member is not the family's
     * @throws BadRequestException       if the default is not a switched-on, writable calendar,
     *                                   or a calendar no account can read is switched on
     */
    public GoogleCalendarSettingsResponse update(Family family, UpdateGoogleCalendarSettingsRequest request) {
        return transactions.execute(status -> {
            for (UpdateGoogleCalendarSettingsRequest.CalendarSetting setting :
                    request.calendars() == null ? List.<UpdateGoogleCalendarSettingsRequest.CalendarSetting>of()
                            : request.calendars()) {
                GoogleCalendar calendar = familysCalendar(family, setting.id());
                Set<FamilyMember> lanes = new HashSet<>();
                for (UUID memberId : setting.laneMemberIds() == null ? List.<UUID>of() : setting.laneMemberIds()) {
                    FamilyMember member = members.findById(memberId)
                            .filter(m -> m.getFamily().getId().equals(family.getId()))
                            .orElseThrow(() -> new ResourceNotFoundException("Family Member", memberId));
                    lanes.add(member);
                }
                calendar.getLaneMembers().clear();
                calendar.getLaneMembers().addAll(lanes);
                if (setting.enabled() && !calendar.isEnabled()) {
                    if (calendar.getPollingToken() == null) {
                        throw new BadRequestException("No signed-in account can read \"" + calendar.getSummary()
                                + "\". Connect Google again for the member who shares it.");
                    }
                    calendar.setEnabled(true);
                    calendar.setSyncToken(null);
                    calendar.setFullSyncRequired(true);
                } else if (!setting.enabled() && calendar.isEnabled()) {
                    switchOff(calendar);
                }
            }
            if (request.defaultCalendarId() != null) {
                GoogleCalendar chosen = familysCalendar(family, request.defaultCalendarId());
                boolean writable = access.findByCalendarId(chosen.getId()).stream()
                        .anyMatch(GoogleCalendarAdminService::canWrite);
                if (!chosen.isEnabled() || !writable) {
                    throw new BadRequestException("New events can only go on a calendar that is switched on and "
                            + "that the hub may write to.");
                }
                families.findById(family.getId()).orElseThrow().setDefaultGoogleCalendarId(chosen.getId());
            }
            calendars.flush();
            return read(family.getId());
        });
    }

    private GoogleCalendar familysCalendar(Family family, UUID calendarId) {
        return calendars.findById(calendarId)
                .filter(calendar -> calendar.getFamily().getId().equals(family.getId()))
                .orElseThrow(() -> new ResourceNotFoundException("Google calendar", calendarId));
    }

    /**
     * Whether the family's calendars are up to date, for the calendar's banner.
     *
     * @param family the signed-in family
     * @return the accounts' state and each enabled calendar's last sync
     */
    public GoogleHealthResponse health(Family family) {
        return transactions.execute(status -> {
            List<GoogleOAuthToken> accounts = tokens.findByFamilyId(family.getId());
            List<GoogleCalendar> shown = calendars.findEnabledByFamily(family.getId());
            Instant oldest = shown.stream().map(GoogleCalendar::getLastSyncedAt).filter(Objects::nonNull)
                    .min(Comparator.naturalOrder()).orElse(null);
            return new GoogleHealthResponse(
                    !accounts.isEmpty(),
                    accounts.stream().anyMatch(GoogleOAuthToken::isNeedsReauth),
                    accounts.stream().anyMatch(token -> !GoogleScopes.canWrite(token.getScope())),
                    oldest,
                    shown.stream().map(calendar -> new GoogleHealthResponse.CalendarHealth(calendar.getId(),
                            calendar.getSummary(), calendar.getLastSyncedAt(), calendar.getLastSyncError())).toList());
        });
    }

    private static String googleSpelling(GoogleAccessRole role) {
        return switch (role) {
            case OWNER -> "owner";
            case WRITER -> "writer";
            case READER -> "reader";
            case FREE_BUSY_READER -> "freeBusyReader";
        };
    }
}
