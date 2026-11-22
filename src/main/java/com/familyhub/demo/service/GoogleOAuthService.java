package com.familyhub.demo.service;

import com.familyhub.demo.exception.ServiceUnavailableException;
import com.familyhub.demo.config.GoogleOAuthConfig;
import com.familyhub.demo.dto.GoogleConnectionStatus;
import com.familyhub.demo.dto.GoogleTokenResponse;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.model.EventSource;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.GoogleOAuthToken;
import com.familyhub.demo.repository.CalendarEventRepository;
import com.familyhub.demo.repository.FamilyMemberRepository;
import com.familyhub.demo.repository.GoogleOAuthTokenRepository;
import com.familyhub.demo.repository.GoogleSyncedCalendarRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import com.familyhub.demo.event.GoogleAccountConnected;
import com.familyhub.demo.google.GoogleScopes;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Slf4j
@Service
@Transactional(readOnly = true)
public class GoogleOAuthService {
    private static final String AUTH_URL = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String REVOKE_URL = "https://oauth2.googleapis.com/revoke";
    // Phase 4: the events scope lets the hub write; accounts that signed in with the old
    // read-only scope keep syncing and are asked to sign in again.
    private static final String SCOPES = GoogleScopes.REQUESTED;

    private final GoogleOAuthConfig config;
    private final GoogleOAuthTokenRepository tokenRepository;
    private final FamilyMemberRepository memberRepository;
    private final TokenEncryptionService encryptionService;
    private final OAuthStateStore stateStore;
    private final GoogleSyncedCalendarRepository syncedCalendarRepository;
    private final CalendarEventRepository calendarEventRepository;
    private final ApplicationEventPublisher events;
    private final GoogleCalendarAdminService calendarAdmin;
    private final RestClient restClient;

    @Autowired
    public GoogleOAuthService(GoogleOAuthConfig config,
                              GoogleOAuthTokenRepository tokenRepository,
                              FamilyMemberRepository memberRepository,
                              TokenEncryptionService encryptionService,
                              OAuthStateStore stateStore,
                              GoogleSyncedCalendarRepository syncedCalendarRepository,
                              CalendarEventRepository calendarEventRepository,
                              ApplicationEventPublisher events,
                              GoogleCalendarAdminService calendarAdmin) {
        this(config, tokenRepository, memberRepository, encryptionService, stateStore,
                syncedCalendarRepository, calendarEventRepository, events, calendarAdmin, RestClient.create());
    }

    public GoogleOAuthService(GoogleOAuthConfig config,
                              GoogleOAuthTokenRepository tokenRepository,
                              FamilyMemberRepository memberRepository,
                              TokenEncryptionService encryptionService,
                              OAuthStateStore stateStore,
                              GoogleSyncedCalendarRepository syncedCalendarRepository,
                              CalendarEventRepository calendarEventRepository,
                              ApplicationEventPublisher events,
                              GoogleCalendarAdminService calendarAdmin,
                              RestClient restClient) {
        this.config = config;
        this.tokenRepository = tokenRepository;
        this.memberRepository = memberRepository;
        this.encryptionService = encryptionService;
        this.stateStore = stateStore;
        this.syncedCalendarRepository = syncedCalendarRepository;
        this.calendarEventRepository = calendarEventRepository;
        this.events = events;
        this.calendarAdmin = calendarAdmin;
        this.restClient = restClient;
    }

    public String buildAuthorizationUrl(UUID memberId) {
        // Without a client the link would carry an empty client_id and Google would show
        // its own "Access blocked" page; say what is missing instead.
        if (isBlank(config.getClientId()) || isBlank(config.getClientSecret())) {
            throw new ServiceUnavailableException(
                    "Google Calendar isn't set up on this server yet. An administrator needs to add "
                            + "GOOGLE_CLIENT_ID and GOOGLE_CLIENT_SECRET.");
        }
        String state = stateStore.generateState(memberId);
        return AUTH_URL + "?" +
                "client_id=" + encode(config.getClientId()) +
                "&redirect_uri=" + encode(config.getRedirectUri()) +
                "&response_type=code" +
                "&scope=" + encode(SCOPES) +
                "&access_type=offline" +
                "&prompt=consent" +
                "&state=" + encode(state);
    }

    public Optional<UUID> consumeState(String state) {
        return stateStore.consumeState(state);
    }

    @Transactional
    public GoogleOAuthToken exchangeCodeForTokens(String code, UUID memberId) {
        FamilyMember member = memberRepository.findById(memberId)
                .orElseThrow(() -> new ResourceNotFoundException("Family Member", memberId));

        GoogleTokenResponse response = restClient.post()
                .uri(TOKEN_URL)
                .header("Content-Type", "application/x-www-form-urlencoded")
                .body("code=" + encode(code) +
                        "&client_id=" + encode(config.getClientId()) +
                        "&client_secret=" + encode(config.getClientSecret()) +
                        "&redirect_uri=" + encode(config.getRedirectUri()) +
                        "&grant_type=authorization_code")
                .retrieve()
                .body(GoogleTokenResponse.class);

        if (response == null || response.accessToken() == null) {
            throw new BadRequestException("Failed to exchange authorization code for tokens");
        }

        if (response.refreshToken() == null) {
            throw new BadRequestException("Google did not return a refresh token");
        }

        // A reconnect updates the account's row in place: cached calendars and their access
        // point at it, and a new row would leave them with no account to poll them.
        GoogleOAuthToken token = tokenRepository.findByMemberId(memberId).orElse(null);
        if (token == null) {
            token = new GoogleOAuthToken();
            token.setMember(member);
        } else {
            // The pre-Phase 4 sync's rows went with the old row; they still go. Removed in C10.
            syncedCalendarRepository.deleteByMemberId(memberId);
        }
        token.setAccessToken(encryptionService.encrypt(response.accessToken()));
        token.setRefreshToken(encryptionService.encrypt(response.refreshToken()));
        token.setTokenExpiry(Instant.now().plusSeconds(
                response.expiresIn() != null ? response.expiresIn() : 3600));
        token.setScope(response.scope() != null ? response.scope() : SCOPES);
        token.setNeedsReauth(false);

        GoogleOAuthToken saved = tokenRepository.save(token);
        events.publishEvent(new GoogleAccountConnected(saved.getId()));
        return saved;
    }

    @Transactional
    public void disconnect(UUID memberId) {
        tokenRepository.findByMemberId(memberId).ifPresent(token -> {
            // Best-effort revoke the refresh token at Google (more durable than access token)
            try {
                String refreshToken = encryptionService.decrypt(token.getRefreshToken());
                restClient.post()
                        .uri(REVOKE_URL + "?token=" + encode(refreshToken))
                        .header("Content-Type", "application/x-www-form-urlencoded")
                        .retrieve()
                        .toBodilessEntity();
            } catch (Exception e) {
                log.warn("Failed to revoke Google token for member {}: {}", memberId, e.getMessage());
            }

            // Its calendars go to another account that sees them, or switch off.
            calendarAdmin.accountRemoved(token.getId());
            // Delete synced calendar rows (CASCADE from token would handle this, but be explicit)
            syncedCalendarRepository.deleteByMemberId(memberId);

            // Delete all Google-sourced calendar events for this member
            calendarEventRepository.deleteByMemberAndSource(token.getMember(), EventSource.GOOGLE);

            tokenRepository.delete(token);
        });
    }

    public boolean isConnected(UUID memberId) {
        return tokenRepository.findByMemberId(memberId).isPresent();
    }

    public GoogleConnectionStatus getConnectionStatus(UUID memberId) {
        boolean connected = isConnected(memberId);

        List<GoogleConnectionStatus.SyncedCalendarInfo> calendars = connected
                ? syncedCalendarRepository.findByMemberId(memberId).stream()
                    .map(sc -> new GoogleConnectionStatus.SyncedCalendarInfo(
                            sc.getGoogleCalendarId(),
                            sc.getCalendarName(),
                            sc.isEnabled(),
                            sc.getLastSyncedAt()))
                    .toList()
                : List.of();

        return new GoogleConnectionStatus(connected, calendars);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
