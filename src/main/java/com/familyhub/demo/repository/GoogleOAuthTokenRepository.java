package com.familyhub.demo.repository;

import com.familyhub.demo.model.GoogleOAuthToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface GoogleOAuthTokenRepository extends JpaRepository<GoogleOAuthToken, UUID> {
    Optional<GoogleOAuthToken> findByMemberId(UUID memberId);

    /**
     * Stores a refreshed access token. A bulk update, so it works from a credentials
     * listener on any thread without a loaded entity; {@code updatedAt} is set here
     * because bulk updates skip {@code @PreUpdate}.
     *
     * @param id          the token's id
     * @param accessToken the new access token, encrypted
     * @param expiry      when it expires
     * @param now         the update time
     * @return the number of rows changed (0 if the account was disconnected meanwhile)
     */
    @Modifying
    @Transactional
    @Query("update GoogleOAuthToken t set t.accessToken = :accessToken, t.tokenExpiry = :expiry, "
            + "t.updatedAt = :now where t.id = :id")
    int updateAccessToken(@Param("id") UUID id, @Param("accessToken") String accessToken,
                          @Param("expiry") Instant expiry, @Param("now") Instant now);

    /**
     * Marks an account whose sign-in Google refused; its calendars wait until it signs in
     * again (a reconnect replaces the row).
     *
     * @param id the token's id
     * @return the number of rows changed
     */
    @Modifying
    @Transactional
    @Query("update GoogleOAuthToken t set t.needsReauth = true where t.id = :id")
    int markNeedsReauth(@Param("id") UUID id);
}
