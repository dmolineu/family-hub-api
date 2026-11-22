package com.familyhub.demo.integration;

import com.familyhub.demo.config.TestcontainersConfig;
import com.familyhub.demo.dto.calendar.GoogleCalendarSettingResponse;
import com.familyhub.demo.dto.calendar.GoogleCalendarSettingsResponse;
import com.familyhub.demo.dto.calendar.GoogleHealthResponse;
import com.familyhub.demo.dto.calendar.UpdateGoogleCalendarSettingsRequest;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.google.FakeGoogleCalendarGateway;
import com.familyhub.demo.google.GoogleScopes;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyColor;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.GoogleAccessRole;
import com.familyhub.demo.model.GoogleCalendar;
import com.familyhub.demo.model.GoogleOAuthToken;
import com.familyhub.demo.repository.FamilyMemberRepository;
import com.familyhub.demo.repository.FamilyRepository;
import com.familyhub.demo.repository.GoogleCalendarRepository;
import com.familyhub.demo.repository.GoogleEventRepository;
import com.familyhub.demo.repository.GoogleOAuthTokenRepository;
import com.familyhub.demo.service.CalendarCacheSync;
import com.familyhub.demo.service.GoogleCalendarAdminService;
import com.google.api.client.util.DateTime;
import com.google.api.services.calendar.model.Event;
import com.google.api.services.calendar.model.EventDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Family Settings' Google calendars (Phase 4): what each signed-in account can see is
 * read from Google and cached once per family, new calendars start switched off, a
 * parent switches calendars on, says whose lanes their unmarked events go in and picks
 * the default calendar for new events (one the hub can write to). Switching a calendar
 * off drops its cached events; an account that goes hands its calendars to another
 * account that sees them, or they switch off.
 */
@SpringBootTest
@Import({TestcontainersConfig.class, GoogleCalendarAdminIntegrationTest.Config.class})
@ActiveProfiles("test")
class GoogleCalendarAdminIntegrationTest {

    @TestConfiguration
    static class Config {
        @Bean
        @Primary
        FakeGoogleCalendarGateway adminFakeGoogle() {
            return new FakeGoogleCalendarGateway();
        }
    }

    @Autowired
    FakeGoogleCalendarGateway google;

    @Autowired
    GoogleCalendarAdminService admin;

    @Autowired
    CalendarCacheSync sync;

    @Autowired
    FamilyRepository families;

    @Autowired
    FamilyMemberRepository members;

    @Autowired
    GoogleOAuthTokenRepository tokens;

    @Autowired
    GoogleCalendarRepository calendars;

    @Autowired
    GoogleEventRepository events;

    private Family family;
    private FamilyMember dad;
    private FamilyMember maya;
    private GoogleOAuthToken dadsAccount;
    private String primaryId;
    private String hubId;
    private String schoolId;

    @BeforeEach
    void setUp() {
        family = family("admin");
        dad = member(family, "Dad");
        maya = member(family, "Maya");
        dadsAccount = account(dad, GoogleScopes.REQUESTED);
        String run = UUID.randomUUID().toString();
        primaryId = "dad-" + run + "@example.com";
        hubId = "hub-" + run + "@group.calendar.google.com";
        schoolId = "school-" + run + "@group.calendar.google.com";
        google.addCalendar(primaryId, primaryId, "America/Los_Angeles");
        google.addCalendar(hubId, "Hub", "America/Los_Angeles");
        google.addCalendar(schoolId, "School", "America/Chicago");
        google.grant(dadsAccount, primaryId, GoogleAccessRole.OWNER);
        google.grant(dadsAccount, hubId, GoogleAccessRole.OWNER);
        google.grant(dadsAccount, schoolId, GoogleAccessRole.READER);
        google.markPrimary(dadsAccount, primaryId);
    }

    private Family family(String name) {
        Family created = new Family();
        created.setName(name);
        created.setUsername(name + "-" + UUID.randomUUID());
        created.setPasswordHash("$2a$10$dummyhashfortesting");
        return families.save(created);
    }

    private FamilyMember member(Family owner, String name) {
        FamilyMember member = new FamilyMember();
        member.setFamily(owner);
        member.setName(name);
        member.setColor(FamilyColor.CORAL);
        return members.save(member);
    }

    private GoogleOAuthToken account(FamilyMember member, String scope) {
        GoogleOAuthToken token = new GoogleOAuthToken();
        token.setMember(member);
        token.setAccessToken("enc-access");
        token.setRefreshToken("enc-refresh");
        token.setTokenExpiry(Instant.now().plusSeconds(3600));
        token.setScope(scope);
        return tokens.save(token);
    }

    private GoogleCalendarSettingResponse setting(GoogleCalendarSettingsResponse settings, String googleId) {
        return settings.calendars().stream().filter(c -> c.googleCalendarId().equals(googleId)).findFirst()
                .orElseThrow();
    }

    private UpdateGoogleCalendarSettingsRequest.CalendarSetting on(GoogleCalendarSettingsResponse settings,
                                                                    String googleId, FamilyMember... lanes) {
        return new UpdateGoogleCalendarSettingsRequest.CalendarSetting(setting(settings, googleId).id(), true,
                java.util.Arrays.stream(lanes).map(FamilyMember::getId).toList());
    }

    @Test
    void whatAnAccountSeesIsCachedSwitchedOffWithItsRolesAndTheAccountsAddressIsLearned() {
        admin.discover(dadsAccount.getId());

        GoogleCalendarSettingsResponse settings = admin.settings(family);
        assertThat(settings.calendars()).extracting(GoogleCalendarSettingResponse::summary)
                .containsExactlyInAnyOrder(primaryId, "Hub", "School");
        assertThat(settings.calendars()).allSatisfy(c -> assertThat(c.enabled()).isFalse());
        assertThat(setting(settings, hubId).accessRole()).isEqualTo("owner");
        assertThat(setting(settings, hubId).writable()).isTrue();
        assertThat(setting(settings, schoolId).accessRole()).isEqualTo("reader");
        assertThat(setting(settings, schoolId).writable()).isFalse();
        assertThat(setting(settings, schoolId).timeZone()).isEqualTo("America/Chicago");
        assertThat(setting(settings, hubId).color()).isEqualTo("#9fc6e7");
        assertThat(settings.defaultCalendarId()).isNull();
        assertThat(tokens.findById(dadsAccount.getId()).orElseThrow().getGoogleAccountEmail()).isEqualTo(primaryId);
        assertThat(calendars.findEnabledByFamily(family.getId())).isEmpty();
    }

    @Test
    void aParentSwitchesCalendarsOnSetsTheirLanesAndPicksAWritableDefault() {
        admin.discover(dadsAccount.getId());
        GoogleCalendarSettingsResponse before = admin.settings(family);

        GoogleCalendarSettingsResponse after = admin.update(family, new UpdateGoogleCalendarSettingsRequest(
                List.of(on(before, hubId, dad, maya), on(before, schoolId, maya)), setting(before, hubId).id()));

        assertThat(setting(after, hubId).enabled()).isTrue();
        assertThat(setting(after, hubId).laneMemberIds()).containsExactlyInAnyOrder(dad.getId(), maya.getId());
        assertThat(setting(after, schoolId).laneMemberIds()).containsExactly(maya.getId());
        assertThat(after.defaultCalendarId()).isEqualTo(setting(before, hubId).id());
        assertThat(setting(after, hubId).isDefault()).isTrue();

        assertThatThrownBy(() -> admin.update(family, new UpdateGoogleCalendarSettingsRequest(List.of(),
                setting(before, schoolId).id()))).isInstanceOf(BadRequestException.class);
        assertThatThrownBy(() -> admin.update(family, new UpdateGoogleCalendarSettingsRequest(List.of(),
                setting(before, primaryId).id()))).isInstanceOf(BadRequestException.class);
    }

    @Test
    void readingGoogleAgainUpdatesRolesKeepsChoicesAndLetsGoOfCalendarsTheAccountLost() {
        admin.discover(dadsAccount.getId());
        GoogleCalendarSettingsResponse before = admin.settings(family);
        admin.update(family, new UpdateGoogleCalendarSettingsRequest(
                List.of(on(before, hubId, maya), on(before, schoolId, maya), on(before, primaryId, dad)), null));

        google.grant(dadsAccount, schoolId, GoogleAccessRole.WRITER);
        google.rename(hubId, "Family hub");
        google.revoke(dadsAccount, primaryId);
        admin.discover(dadsAccount.getId());

        GoogleCalendarSettingsResponse after = admin.settings(family);
        assertThat(setting(after, hubId).summary()).isEqualTo("Family hub");
        assertThat(setting(after, hubId).enabled()).isTrue();
        assertThat(setting(after, hubId).laneMemberIds()).containsExactly(maya.getId());
        assertThat(setting(after, schoolId).writable()).isTrue();
        // No account sees it any more, so it is switched off rather than left unsynced.
        GoogleCalendarSettingResponse lost = setting(after, primaryId);
        assertThat(lost.enabled()).isFalse();
        assertThat(lost.accessRole()).isNull();
    }

    @Test
    void switchingACalendarOffDropsItsEventsAndItsNextSyncIsAFullOne() {
        admin.discover(dadsAccount.getId());
        GoogleCalendarSettingsResponse before = admin.settings(family);
        admin.update(family, new UpdateGoogleCalendarSettingsRequest(List.of(on(before, hubId, dad)), null));
        google.insertEvent(dadsAccount, hubId, new Event().setSummary("Swim")
                .setStart(new EventDateTime().setDateTime(new DateTime("2026-12-03T17:00:00Z")))
                .setEnd(new EventDateTime().setDateTime(new DateTime("2026-12-03T18:00:00Z"))), false);
        UUID hub = setting(before, hubId).id();
        assertThat(sync.syncCalendar(hub)).isEqualTo(CalendarCacheSync.Outcome.SYNCED);
        assertThat(events.findAllByCalendarId(hub)).hasSize(1);

        admin.update(family, new UpdateGoogleCalendarSettingsRequest(List.of(
                new UpdateGoogleCalendarSettingsRequest.CalendarSetting(hub, false, List.of(dad.getId()))), null));

        assertThat(events.findAllByCalendarId(hub)).isEmpty();
        GoogleCalendar off = calendars.findById(hub).orElseThrow();
        assertThat(off.isFullSyncRequired()).isTrue();
        assertThat(off.getSyncToken()).isNull();
    }

    @Test
    void anotherFamilysCalendarCannotBeChanged() {
        admin.discover(dadsAccount.getId());
        UUID hub = setting(admin.settings(family), hubId).id();

        assertThatThrownBy(() -> admin.update(family("other"), new UpdateGoogleCalendarSettingsRequest(List.of(
                new UpdateGoogleCalendarSettingsRequest.CalendarSetting(hub, true, List.of())), null)))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void anAccountThatGoesHandsItsCalendarsToAnotherAccountOrTheySwitchOff() {
        FamilyMember mom = member(family, "Mom");
        GoogleOAuthToken momsAccount = account(mom, GoogleScopes.REQUESTED);
        google.grant(momsAccount, hubId, GoogleAccessRole.WRITER);
        admin.discover(dadsAccount.getId());
        admin.discover(momsAccount.getId());
        GoogleCalendarSettingsResponse before = admin.settings(family);
        admin.update(family, new UpdateGoogleCalendarSettingsRequest(
                List.of(on(before, hubId, dad), on(before, schoolId, maya)), setting(before, hubId).id()));

        admin.accountRemoved(dadsAccount.getId());

        GoogleCalendar hub = calendars.findForSync(setting(before, hubId).id()).orElseThrow();
        assertThat(hub.isEnabled()).isTrue();
        assertThat(hub.getPollingToken().getId()).isEqualTo(momsAccount.getId());
        GoogleCalendar school = calendars.findForSync(setting(before, schoolId).id()).orElseThrow();
        assertThat(school.isEnabled()).isFalse();
        assertThat(school.getPollingToken()).isNull();
    }

    @Test
    void healthSaysWhenAnAccountMustSignInAgainOrCannotWriteYet() {
        GoogleOAuthToken readOnly = account(member(family, "Mom"), "https://www.googleapis.com/auth/calendar.events.readonly");
        google.grant(readOnly, hubId, GoogleAccessRole.OWNER);
        admin.discover(readOnly.getId());

        GoogleHealthResponse health = admin.health(family);
        assertThat(health.connected()).isTrue();
        assertThat(health.writeScopeMissing()).isTrue();
        assertThat(health.needsReauth()).isFalse();

        tokens.markNeedsReauth(readOnly.getId());
        assertThat(admin.health(family).needsReauth()).isTrue();
        assertThat(admin.health(family("nobody")).connected()).isFalse();
    }
}
