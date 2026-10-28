package com.familyhub.demo.service;

import com.familyhub.demo.dto.AuthResponse;
import com.familyhub.demo.dto.ChangePasswordRequest;
import com.familyhub.demo.dto.LoginRequest;
import com.familyhub.demo.dto.RegisterRequest;
import com.familyhub.demo.dto.UsernameCheckResponse;
import com.familyhub.demo.exception.BadRequestException;
import com.familyhub.demo.exception.InvalidCredentialException;
import com.familyhub.demo.exception.ResourceNotFoundException;
import com.familyhub.demo.exception.UsernameAlreadyExists;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.repository.FamilyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import com.familyhub.demo.exception.TooManyAttemptsException;
import com.familyhub.demo.security.LoginAttemptLimiter;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.List;
import java.util.Optional;

import static com.familyhub.demo.TestDataFactory.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private FamilyRepository familyRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private JwtService jwtService;

    @Mock
    private ListSeedService listSeedService;

    @Mock
    private RecipeSeedService recipeSeedService;

    @Mock
    private LoginAttemptLimiter loginAttemptLimiter;

    @InjectMocks
    private AuthService authService;

    private Family family;

    @BeforeEach
    void setUp() {
        family = createFamily();
        family.setFamilyMembers(List.of());
    }

    @Test
    void register_success_seedsStarterRecipes() {
        RegisterRequest request = createRegisterRequest();
        when(familyRepository.existsByUsername(request.username())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hash");
        when(familyRepository.saveAndFlush(any(Family.class))).thenReturn(family);
        when(jwtService.generateToken(family)).thenReturn("token");

        authService.register(request);

        verify(recipeSeedService).seedStarterRecipes(family);
    }

    @Test
    void register_starterRecipesFalse_skipsSeeding() {
        RegisterRequest base = createRegisterRequest();
        RegisterRequest request = new RegisterRequest(
                base.username(), base.password(), base.familyName(), base.members(), base.timezone(), Boolean.FALSE);
        when(familyRepository.existsByUsername(request.username())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("hash");
        when(familyRepository.saveAndFlush(any(Family.class))).thenReturn(family);
        when(jwtService.generateToken(family)).thenReturn("token");

        authService.register(request);

        verify(recipeSeedService, never()).seedStarterRecipes(any());
    }

    @Test
    void register_success_savesAndReturnsToken() {
        RegisterRequest request = createRegisterRequest();
        when(familyRepository.existsByUsername(request.username())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-password");
        when(familyRepository.saveAndFlush(any(Family.class))).thenReturn(family);
        when(jwtService.generateToken(family)).thenReturn("jwt-token");

        AuthResponse result = authService.register(request);

        assertThat(result.token()).isEqualTo("jwt-token");
        assertThat(result.family().id()).isEqualTo(FAMILY_ID);
    }

    @Test
    void register_success_seedsListsModuleDefaults() {
        RegisterRequest request = createRegisterRequest();
        when(familyRepository.existsByUsername(request.username())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-password");
        when(familyRepository.saveAndFlush(any(Family.class))).thenReturn(family);
        when(jwtService.generateToken(family)).thenReturn("jwt-token");

        authService.register(request);

        verify(listSeedService).seedDefaultsForFamily(family);
    }

    @Test
    void register_withExplicitTimezone_persistsTimezone() {
        RegisterRequest request = new RegisterRequest(
                "smithfamily",
                "password123",
                "Smith Family",
                List.of(createFamilyMemberRequest()),
                "America/Chicago"
        );
        when(familyRepository.existsByUsername(request.username())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-password");
        when(familyRepository.saveAndFlush(any(Family.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jwtService.generateToken(any(Family.class))).thenReturn("jwt-token");

        authService.register(request);

        verify(familyRepository).saveAndFlush(argThat(saved ->
                "America/Chicago".equals(saved.getTimezone())
        ));
    }

    @Test
    void register_withoutTimezone_fallsBackToPacificDefault() {
        RegisterRequest request = new RegisterRequest(
                "smithfamily",
                "password123",
                "Smith Family",
                List.of(createFamilyMemberRequest()),
                null
        );
        when(familyRepository.existsByUsername(request.username())).thenReturn(false);
        when(passwordEncoder.encode(anyString())).thenReturn("encoded-password");
        when(familyRepository.saveAndFlush(any(Family.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(jwtService.generateToken(any(Family.class))).thenReturn("jwt-token");

        authService.register(request);

        verify(familyRepository).saveAndFlush(argThat(saved ->
                "America/Los_Angeles".equals(saved.getTimezone())
        ));
    }

    @Test
    void register_withInvalidTimezone_throwsBadRequest() {
        RegisterRequest request = new RegisterRequest(
                "smithfamily",
                "password123",
                "Smith Family",
                List.of(createFamilyMemberRequest()),
                "Mars/Olympus"
        );
        when(familyRepository.existsByUsername(request.username())).thenReturn(false);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("Timezone must be a valid IANA timezone.");
    }

    @Test
    void register_duplicateUsername_throwsUsernameAlreadyExists() {
        RegisterRequest request = createRegisterRequest();
        when(familyRepository.existsByUsername(request.username())).thenReturn(true);

        assertThatThrownBy(() -> authService.register(request))
                .isInstanceOf(UsernameAlreadyExists.class);
    }

    @Test
    void login_success_returnsToken() {
        LoginRequest request = createLoginRequest();
        when(familyRepository.findByUsername(request.username())).thenReturn(Optional.of(family));
        when(passwordEncoder.matches(request.password(), family.getPassword())).thenReturn(true);
        when(jwtService.generateToken(family)).thenReturn("jwt-token");

        AuthResponse result = authService.login(request);

        assertThat(result.token()).isEqualTo("jwt-token");
        assertThat(result.family().id()).isEqualTo(FAMILY_ID);
    }

    @Test
    void login_invalidPassword_throwsInvalidCredential() {
        LoginRequest request = createLoginRequest();
        when(familyRepository.findByUsername(request.username())).thenReturn(Optional.of(family));
        when(passwordEncoder.matches(request.password(), family.getPassword())).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(InvalidCredentialException.class);
    }

    @Test
    void login_countsAWrongPasswordAndClearsTheCountOnARightOne() {
        LoginRequest request = createLoginRequest();
        when(familyRepository.findByUsername(request.username())).thenReturn(Optional.of(family));
        when(passwordEncoder.matches(request.password(), family.getPassword())).thenReturn(false, true);
        when(jwtService.generateToken(family)).thenReturn("jwt-token");

        assertThatThrownBy(() -> authService.login(request)).isInstanceOf(InvalidCredentialException.class);
        verify(loginAttemptLimiter).recordFailure(request.username());

        authService.login(request);
        verify(loginAttemptLimiter).recordSuccess(request.username());
    }

    @Test
    void login_whileLocked_refusesWithoutCheckingThePassword() {
        LoginRequest request = createLoginRequest();
        org.mockito.Mockito.doThrow(new TooManyAttemptsException("Too many wrong passwords."))
                .when(loginAttemptLimiter).checkAllowed(request.username());

        assertThatThrownBy(() -> authService.login(request)).isInstanceOf(TooManyAttemptsException.class);
        verify(passwordEncoder, never()).matches(any(), any());
    }

    @Test
    void login_unknownUsername_throwsInvalidCredential() {
        LoginRequest request = createLoginRequest();
        when(familyRepository.findByUsername(request.username())).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request))
                .isInstanceOf(InvalidCredentialException.class);
    }

    @Test
    void checkUsername_available_returnsTrue() {
        when(familyRepository.existsByUsername("newuser")).thenReturn(false);

        UsernameCheckResponse result = authService.checkUsername("newuser");

        assertThat(result.available()).isTrue();
    }

    @Test
    void checkUsername_taken_returnsFalse() {
        when(familyRepository.existsByUsername("testfamily")).thenReturn(true);

        UsernameCheckResponse result = authService.checkUsername("testfamily");

        assertThat(result.available()).isFalse();
    }

    @Test
    void changePassword_wrongCurrentPassword_isRefusedAndChangesNothing() {
        when(familyRepository.findById(family.getId())).thenReturn(Optional.of(family));
        when(passwordEncoder.matches("wrong-password", family.getPasswordHash())).thenReturn(false);

        assertThatThrownBy(() -> authService.changePassword(
                family, new ChangePasswordRequest("wrong-password", "a-new-password")))
                .isInstanceOf(AccessDeniedException.class)
                .hasMessage("That isn't the password this family signs in with.");

        verify(familyRepository, never()).save(any());
        assertThat(family.getPasswordVersion()).isZero();
    }

    @Test
    void changePassword_storesTheNewHash_signsOutOtherDevices_andKeepsThisOne() {
        when(familyRepository.findById(family.getId())).thenReturn(Optional.of(family));
        when(passwordEncoder.matches("old-password", family.getPasswordHash())).thenReturn(true);
        when(passwordEncoder.encode("a-new-password")).thenReturn("new-hash");
        when(familyRepository.save(family)).thenReturn(family);
        when(jwtService.generateToken(argThat(f -> f.getPasswordVersion() == 1))).thenReturn("fresh-token");

        AuthResponse response = authService.changePassword(
                family, new ChangePasswordRequest("old-password", "a-new-password"));

        assertThat(family.getPasswordHash()).isEqualTo("new-hash");
        assertThat(family.getPasswordVersion()).isEqualTo(1);
        assertThat(response.token()).isEqualTo("fresh-token");
        assertThat(response.family().name()).isEqualTo("Test Family");
    }

    @Test
    void resetPassword_unknownUsername_saysSo() {
        when(familyRepository.findByUsername("nobody")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.resetPassword("nobody", "a-new-password"))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessage("No family signs in as \"nobody\".");
    }

    @Test
    void resetPassword_tooShortOrTooLong_isRefusedBeforeLookingAnythingUp() {
        assertThatThrownBy(() -> authService.resetPassword("testfamily", "short"))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("A password is 8 to 100 characters.");
        assertThatThrownBy(() -> authService.resetPassword("testfamily", "x".repeat(101)))
                .isInstanceOf(BadRequestException.class)
                .hasMessage("A password is 8 to 100 characters.");

        verify(familyRepository, never()).findByUsername(anyString());
    }

    @Test
    void resetPassword_storesTheNewHash_andSignsOutEveryDevice() {
        when(familyRepository.findByUsername("testfamily")).thenReturn(Optional.of(family));
        when(passwordEncoder.encode("a-new-password")).thenReturn("new-hash");

        authService.resetPassword("testfamily", "a-new-password");

        assertThat(family.getPasswordHash()).isEqualTo("new-hash");
        assertThat(family.getPasswordVersion()).isEqualTo(1);
        verify(familyRepository).save(family);
    }
}
