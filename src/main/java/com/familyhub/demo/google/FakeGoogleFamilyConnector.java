package com.familyhub.demo.google;

import com.familyhub.demo.event.FamilyRegistered;
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
import com.familyhub.demo.service.TokenEncryptionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * The dev profile only ({@code google.calendar.fake}): connects each family that signs up
 * to the in-memory Google, so the browser tests can add and change events with no Google
 * account (Phase 4). The family's first member gets a pretend sign-in that owns one
 * calendar, "Family", which is switched on, in everyone's lane, and the default.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "google.calendar.fake", havingValue = "true")
public class FakeGoogleFamilyConnector {

    private final FakeGoogleCalendarGateway google;
    private final FamilyRepository families;
    private final FamilyMemberRepository members;
    private final GoogleOAuthTokenRepository tokens;
    private final GoogleCalendarRepository calendars;
    private final GoogleCalendarAccessRepository access;
    private final TokenEncryptionService encryption;
    private final TransactionTemplate transactions;

    /**
     * Creates the connector.
     *
     * @param gateway            the in-memory Google (the gateway when faking)
     * @param families           families
     * @param members            their members
     * @param tokens             sign-ins
     * @param calendars          cached calendars
     * @param access             accounts' roles on them
     * @param encryption         stores the pretend tokens like real ones
     * @param transactionManager runs the connection in its own transaction
     */
    public FakeGoogleFamilyConnector(GoogleCalendarGateway gateway, FamilyRepository families,
                                     FamilyMemberRepository members, GoogleOAuthTokenRepository tokens,
                                     GoogleCalendarRepository calendars, GoogleCalendarAccessRepository access,
                                     TokenEncryptionService encryption, PlatformTransactionManager transactionManager) {
        if (!(gateway instanceof FakeGoogleCalendarGateway fake)) {
            throw new IllegalStateException("google.calendar.fake is set but the gateway is not the fake");
        }
        this.google = fake;
        this.families = families;
        this.members = members;
        this.tokens = tokens;
        this.calendars = calendars;
        this.access = access;
        this.encryption = encryption;
        this.transactions = new TransactionTemplate(transactionManager);
        // After the sign-up's commit, so a new transaction is needed to write.
        this.transactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Connects a family that just signed up.
     *
     * @param event which family
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void onRegistered(FamilyRegistered event) {
        transactions.executeWithoutResult(status -> connect(event.familyId()));
    }

    private void connect(UUID familyId) {
        Family family = families.findById(familyId).orElseThrow();
        List<FamilyMember> familyMembers = members.findByFamily(family);
        if (familyMembers.isEmpty()) {
            return;
        }
        GoogleOAuthToken account = new GoogleOAuthToken();
        account.setMember(familyMembers.getFirst());
        account.setAccessToken(encryption.encrypt("fake-access"));
        account.setRefreshToken(encryption.encrypt("fake-refresh"));
        account.setTokenExpiry(Instant.now().plusSeconds(365L * 24 * 3600));
        account.setScope(GoogleScopes.REQUESTED);
        account = tokens.save(account);
        String googleId = "family-" + familyId + "@fake.familyhub";
        google.addCalendar(googleId, "Family", family.getTimezone());
        google.grant(account, googleId, GoogleAccessRole.OWNER);
        GoogleCalendar calendar = new GoogleCalendar();
        calendar.setFamily(family);
        calendar.setGoogleCalendarId(googleId);
        calendar.setSummary("Family");
        calendar.setTimeZone(family.getTimezone());
        calendar.setPollingToken(account);
        calendar.setLaneMembers(new HashSet<>(familyMembers));
        calendar = calendars.save(calendar);
        access.save(new GoogleCalendarAccess(calendar, account, GoogleAccessRole.OWNER));
        family.setDefaultGoogleCalendarId(calendar.getId());
        log.info("Connected a new family to the in-memory Google, familyId={}", familyId);
    }
}
