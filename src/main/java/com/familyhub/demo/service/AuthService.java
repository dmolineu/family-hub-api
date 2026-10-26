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
import com.familyhub.demo.mapper.FamilyMapper;
import com.familyhub.demo.mapper.FamilyMemberMapper;
import com.familyhub.demo.model.Family;
import com.familyhub.demo.repository.FamilyRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {
    /** Same bounds as sign-up ({@link RegisterRequest}). */
    private static final int MIN_PASSWORD_LENGTH = 8;
    private static final int MAX_PASSWORD_LENGTH = 100;

    private final FamilyRepository familyRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final ListSeedService listSeedService;
    private final RecipeSeedService recipeSeedService;

    @Transactional
    public AuthResponse register(RegisterRequest registerRequest) {
        // Check if username already exists
        if (familyRepository.existsByUsername(registerRequest.username())) {
            throw new UsernameAlreadyExists(registerRequest.username());
        }

        // Hash password then save to db
        Family family = new Family();
        family.setName(registerRequest.familyName());
        family.setUsername(registerRequest.username());
        family.setPasswordHash(passwordEncoder.encode(registerRequest.password()));
        family.setTimezone(FamilyTimezoneResolver.normalizeRequestedTimezone(registerRequest.timezone()));
        family.setFamilyMembers(
                registerRequest.members().stream()
                        .map(request -> FamilyMemberMapper.toEntity(request, family))
                        .toList()
        );
        Family saved = familyRepository.saveAndFlush(family);
        listSeedService.seedDefaultsForFamily(saved);
        if (registerRequest.wantsStarterRecipes()) {
            recipeSeedService.seedStarterRecipes(saved);
        }
        log.info("Family registered, familyId={}, username={}", saved.getId(), saved.getUsername());

        // Create JWT and return response
        String token = jwtService.generateToken(saved);

        return new AuthResponse(token, FamilyMapper.toDto(saved));
    }

    @Transactional
    public AuthResponse login(LoginRequest loginRequest) {
        Family family = familyRepository.findByUsername(loginRequest.username())
                .orElse(null);

        if (family == null || !passwordEncoder.matches(loginRequest.password(), family.getPassword())) {
            throw new InvalidCredentialException("Invalid username or password.");
        }

        String token = jwtService.generateToken(family);
        log.info("Family logged in, familyId={}, username={}", family.getId(), family.getUsername());
        return new AuthResponse(token, FamilyMapper.toDto(family));
    }

    /**
     * Changes the signed-in family's password after checking the current one, and
     * signs out every other device by bumping the password version.
     *
     * @return a fresh token for this device, so the person changing it stays signed in
     */
    @Transactional
    public AuthResponse changePassword(Family signedIn, ChangePasswordRequest request) {
        // The principal was loaded by the token filter outside this transaction.
        Family family = familyRepository.findById(signedIn.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Family", signedIn.getId()));
        // 403, not 401: the web client signs out on any 401, and a typo here must
        // not throw the person off the device they are using.
        if (!passwordEncoder.matches(request.currentPassword(), family.getPasswordHash())) {
            throw new AccessDeniedException("That isn't the password this family signs in with.");
        }
        applyNewPassword(family, request.newPassword());
        log.info("Family password changed, familyId={}, passwordVersion={}", family.getId(), family.getPasswordVersion());
        return new AuthResponse(jwtService.generateToken(family), FamilyMapper.toDto(family));
    }

    /**
     * Sets a family's password without the old one. Only the server's
     * {@code reset-password} command calls this; there is no endpoint for it.
     */
    @Transactional
    public void resetPassword(String username, String newPassword) {
        // The reset command has no request DTO to validate, so the sign-up rule is
        // repeated here.
        if (newPassword == null || newPassword.length() < MIN_PASSWORD_LENGTH
                || newPassword.length() > MAX_PASSWORD_LENGTH) {
            throw new BadRequestException("A password is %d to %d characters."
                    .formatted(MIN_PASSWORD_LENGTH, MAX_PASSWORD_LENGTH));
        }
        Family family = familyRepository.findByUsername(username)
                .orElseThrow(() -> new ResourceNotFoundException("No family signs in as \"%s\".".formatted(username)));
        applyNewPassword(family, newPassword);
        log.info("Family password reset from the server, familyId={}, passwordVersion={}",
                family.getId(), family.getPasswordVersion());
    }

    /** Stores the new hash and bumps the version, which signs out every existing token. */
    private void applyNewPassword(Family family, String newPassword) {
        family.setPasswordHash(passwordEncoder.encode(newPassword));
        family.setPasswordVersion(family.getPasswordVersion() + 1);
        familyRepository.save(family);
    }

    public UsernameCheckResponse checkUsername(String username) {
        return new UsernameCheckResponse(!familyRepository.existsByUsername(username));
    }
}
