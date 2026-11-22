package com.familyhub.demo.config;

import com.familyhub.demo.google.FakeGoogleCalendarGateway;
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
import com.familyhub.demo.repository.GoogleOAuthTokenRepository;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.List;

/**
 * The in-memory Google for tests that need a family's calendar to be in Google (Phase 4),
 * and a way to connect a family to it.
 */
@TestConfiguration(proxyBeanMethods = false)
public class FakeGoogleTestConfig {

    /**
     * The fake Google every calendar call goes to.
     *
     * @return a fresh fake for this test context
     */
    @Bean
    @Primary
    public FakeGoogleCalendarGateway testFakeGoogle() {
        return new FakeGoogleCalendarGateway();
    }

    /**
     * Connects families to the fake Google.
     *
     * @return the helper
     */
    @Bean
    public FakeGoogleFamilies fakeGoogleFamilies(FakeGoogleCalendarGateway google, FamilyRepository families,
                                                 FamilyMemberRepository members, GoogleOAuthTokenRepository tokens,
                                                 GoogleCalendarRepository calendars,
                                                 GoogleCalendarAccessRepository access,
                                                 PlatformTransactionManager transactionManager) {
        return new FakeGoogleFamilies(google, families, members, tokens, calendars, access, transactionManager);
    }

    /** Gives a family one Google account and one calendar new events go on. */
    public static final class FakeGoogleFamilies {
        private final FakeGoogleCalendarGateway google;
        private final FamilyRepository families;
        private final FamilyMemberRepository members;
        private final GoogleOAuthTokenRepository tokens;
        private final GoogleCalendarRepository calendars;
        private final GoogleCalendarAccessRepository access;
        private final TransactionTemplate transactions;

        FakeGoogleFamilies(FakeGoogleCalendarGateway google, FamilyRepository families, FamilyMemberRepository members,
                           GoogleOAuthTokenRepository tokens, GoogleCalendarRepository calendars,
                           GoogleCalendarAccessRepository access, PlatformTransactionManager transactionManager) {
            this.google = google;
            this.families = families;
            this.members = members;
            this.tokens = tokens;
            this.calendars = calendars;
            this.access = access;
            this.transactions = new TransactionTemplate(transactionManager);
        }

        /**
         * Connects a family: its first member signs in to Google with the write scope and
         * owns one calendar, the family's default. Safe to repeat, also from another test
         * context whose fake has never seen the calendar.
         *
         * @param familyId the family
         * @return the family's calendar
         */
        public GoogleCalendar connect(java.util.UUID familyId) {
            return transactions.execute(status -> {
                Family family = families.findById(familyId).orElseThrow();
                List<FamilyMember> familyMembers = members.findByFamily(family);
                FamilyMember owner = familyMembers.getFirst();
                GoogleOAuthToken account = tokens.findByMemberId(owner.getId()).orElseGet(() -> {
                    GoogleOAuthToken token = new GoogleOAuthToken();
                    token.setMember(owner);
                    token.setAccessToken("fake-access");
                    token.setRefreshToken("fake-refresh");
                    token.setTokenExpiry(Instant.now().plusSeconds(3600));
                    token.setScope(GoogleScopes.REQUESTED);
                    return tokens.save(token);
                });
                String googleId = "family-" + familyId + "@group.calendar.google.com";
                google.addCalendar(googleId, "Family", family.getTimezone());
                google.grant(account, googleId, GoogleAccessRole.OWNER);
                GoogleCalendar calendar = calendars.findAllByFamilyId(familyId).stream()
                        .filter(c -> c.getGoogleCalendarId().equals(googleId)).findFirst().orElseGet(() -> {
                            GoogleCalendar created = new GoogleCalendar();
                            created.setFamily(family);
                            created.setGoogleCalendarId(googleId);
                            created.setSummary("Family");
                            created.setTimeZone(family.getTimezone());
                            created.setPollingToken(account);
                            return calendars.save(created);
                        });
                if (access.findById(new GoogleCalendarAccess.Key(calendar.getId(), account.getId())).isEmpty()) {
                    access.save(new GoogleCalendarAccess(calendar, account, GoogleAccessRole.OWNER));
                }
                family.setDefaultGoogleCalendarId(calendar.getId());
                return calendar;
            });
        }
    }
}
