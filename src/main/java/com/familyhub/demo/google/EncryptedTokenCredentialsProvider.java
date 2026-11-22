package com.familyhub.demo.google;

import com.familyhub.demo.config.GoogleOAuthConfig;
import com.familyhub.demo.model.GoogleOAuthToken;
import com.familyhub.demo.repository.GoogleOAuthTokenRepository;
import com.familyhub.demo.service.TokenEncryptionService;
import com.google.api.client.http.HttpTransport;
import com.google.auth.oauth2.AccessToken;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.UserCredentials;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Credentials from a member's stored Google sign-in (Phase 4). Tokens are stored
 * encrypted; a refreshed access token is written back, encrypted, so the next call
 * (every poll) does not refresh again. Replaces the deprecated {@code GoogleCredential}.
 */
@Slf4j
public class EncryptedTokenCredentialsProvider implements GoogleCredentialsProvider {

    private final TokenEncryptionService encryption;
    private final GoogleOAuthConfig oauth;
    private final GoogleOAuthTokenRepository tokens;
    private final HttpTransport transport;

    /**
     * Creates the provider.
     *
     * @param encryption decrypts and encrypts stored tokens
     * @param oauth      the OAuth client the tokens were issued to
     * @param tokens     where refreshed access tokens are stored
     * @param transport  the HTTP transport refreshes go over
     */
    public EncryptedTokenCredentialsProvider(TokenEncryptionService encryption, GoogleOAuthConfig oauth,
                                             GoogleOAuthTokenRepository tokens, HttpTransport transport) {
        this.encryption = encryption;
        this.oauth = oauth;
        this.tokens = tokens;
        this.transport = transport;
    }

    @Override
    public GoogleCredentials forAccount(GoogleOAuthToken account) {
        AccessToken access = new AccessToken(encryption.decrypt(account.getAccessToken()),
                Date.from(account.getTokenExpiry()));
        UserCredentials credentials = UserCredentials.newBuilder()
                .setClientId(oauth.getClientId())
                .setClientSecret(oauth.getClientSecret())
                .setRefreshToken(encryption.decrypt(account.getRefreshToken()))
                .setAccessToken(access)
                .setHttpTransportFactory(() -> transport)
                .build();
        UUID id = account.getId();
        credentials.addChangeListener(changed -> {
            AccessToken fresh = changed.getAccessToken();
            Instant expiry = fresh.getExpirationTime() == null ? Instant.now() : fresh.getExpirationTime().toInstant();
            tokens.updateAccessToken(id, encryption.encrypt(fresh.getTokenValue()), expiry, Instant.now());
            log.info("Refreshed the Google access token, tokenId={}", id);
        });
        return credentials;
    }
}
