package com.familyhub.demo.service;

import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.model.FamilyMember;
import com.familyhub.demo.model.MemberRole;
import com.familyhub.demo.repository.FamilyMemberRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static com.familyhub.demo.TestDataFactory.MEMBER_ID;
import static com.familyhub.demo.TestDataFactory.createFamily;
import static com.familyhub.demo.TestDataFactory.createFamilyMember;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/** The parts of the PIN lockout that need a controllable clock. */
@ExtendWith(MockitoExtension.class)
class ParentPinServiceTest {
    private static final Instant NOW = Instant.parse("2026-10-25T18:00:00Z");

    @Mock
    private FamilyMemberRepository familyMemberRepository;

    @Mock
    private PinAttemptRecorder pinAttemptRecorder;

    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder(4);
    private ParentPinService service;
    private Family family;
    private FamilyMember mom;

    @BeforeEach
    void setUp() {
        service = new ParentPinService(familyMemberRepository, pinAttemptRecorder, passwordEncoder,
                Clock.fixed(NOW, ZoneOffset.UTC));
        family = createFamily();
        mom = createFamilyMember(family);
        mom.setId(MEMBER_ID);
        mom.setName("Mom");
        mom.setRole(MemberRole.PARENT);
        mom.setPinHash(passwordEncoder.encode("2468"));
        when(familyMemberRepository.findById(MEMBER_ID)).thenReturn(Optional.of(mom));
    }

    @Test
    void aLockSaysHowLongIsLeftAndRefusesEvenTheRightPin() {
        mom.setPinLockedUntil(NOW.plusSeconds(14 * 60 + 1));

        assertThatThrownBy(() -> service.verify(family, MEMBER_ID, "2468"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Mom's PIN is locked after too many wrong tries. Try again in 15 minutes.");
        verifyNoInteractions(pinAttemptRecorder);
    }

    @Test
    void anExpiredLockLiftsAndTheRightPinClearsIt() {
        mom.setPinLockedUntil(NOW.minusSeconds(1));

        assertThat(service.verify(family, MEMBER_ID, "2468")).isSameAs(mom);
        verify(pinAttemptRecorder).record(MEMBER_ID, 0, null);
    }

    @Test
    void theFifthWrongTryLocksForFifteenMinutes() {
        mom.setPinFailedAttempts(4);

        assertThatThrownBy(() -> service.verify(family, MEMBER_ID, "0000"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessageContaining("locked for 15 minutes");
        verify(pinAttemptRecorder).record(MEMBER_ID, 0, NOW.plusSeconds(15 * 60));
    }

    @Test
    void aParentWithoutAPinIsToldToSetOne() {
        mom.setPinHash(null);

        assertThatThrownBy(() -> service.verify(family, MEMBER_ID, "2468"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Mom hasn't set a PIN yet. Set one on their profile.");
    }

    @Test
    void aKidCannotApprove() {
        mom.setRole(MemberRole.KID);

        assertThatThrownBy(() -> service.verify(family, MEMBER_ID, "2468"))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("Only a parent can do this.");
    }
}
