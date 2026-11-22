package com.familyhub.demo.google;

import com.familyhub.demo.config.GoogleOAuthConfig;
import com.familyhub.demo.model.GoogleOAuthToken;
import com.familyhub.demo.repository.GoogleOAuthTokenRepository;
import com.familyhub.demo.service.TokenEncryptionService;
import com.google.api.client.http.LowLevelHttpRequest;
import com.google.api.client.http.LowLevelHttpResponse;
import com.google.api.client.json.Json;
import com.google.api.client.testing.http.MockHttpTransport;
import com.google.api.client.testing.http.MockLowLevelHttpRequest;
import com.google.api.client.testing.http.MockLowLevelHttpResponse;
import com.google.auth.oauth2.GoogleCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * An account's credentials come from its stored, encrypted sign-in. A still-valid access
 * token is used as it is; an expired one is refreshed with the refresh token, and the new
 * one is stored (encrypted) so the next poll does not refresh again.
 */
class EncryptedTokenCredentialsProviderTest {

    private static final String KEY = "hZVYYDBsu6sIox9GnjtoSB6Wv3cgjVG5euDC/JVAHZ4=";

    private final TokenEncryptionService encryption = new TokenEncryptionService(KEY);
    private final GoogleOAuthTokenRepository tokens = mock(GoogleOAuthTokenRepository.class);
    private final List<String> tokenRequests = new ArrayList<>();
    private EncryptedTokenCredentialsProvider provider;

    @BeforeEach
    void setUp() {
        GoogleOAuthConfig oauth = new GoogleOAuthConfig();
        oauth.setClientId("client-id");
        oauth.setClientSecret("client-secret");
        MockHttpTransport tokenEndpoint = new MockHttpTransport() {
            @Override
            public LowLevelHttpRequest buildRequest(String method, String url) {
                return new MockLowLevelHttpRequest(url) {
                    @Override
                    public LowLevelHttpResponse execute() throws java.io.IOException {
                        tokenRequests.add(getContentAsString());
                        return new MockLowLevelHttpResponse().setContentType(Json.MEDIA_TYPE)
                                .setContent("{\"access_token\":\"fresh-access\",\"expires_in\":3600,"
                                        + "\"token_type\":\"Bearer\"}");
                    }
                };
            }
        };
        provider = new EncryptedTokenCredentialsProvider(encryption, oauth, tokens, tokenEndpoint);
    }

    private GoogleOAuthToken stored(Instant expiry) {
        GoogleOAuthToken token = new GoogleOAuthToken();
        token.setId(UUID.randomUUID());
        token.setAccessToken(encryption.encrypt("stored-access"));
        token.setRefreshToken(encryption.encrypt("stored-refresh"));
        token.setTokenExpiry(expiry);
        return token;
    }

    @Test
    void aValidAccessTokenIsUsedWithoutRefreshing() throws Exception {
        GoogleCredentials credentials = provider.forAccount(stored(Instant.now().plus(Duration.ofMinutes(30))));

        credentials.refreshIfExpired();

        assertThat(credentials.getAccessToken().getTokenValue()).isEqualTo("stored-access");
        assertThat(tokenRequests).isEmpty();
        verify(tokens, never()).updateAccessToken(any(), any(), any(), any());
    }

    @Test
    void anExpiredAccessTokenIsRefreshedAndTheNewOneStoredEncrypted() throws Exception {
        GoogleOAuthToken token = stored(Instant.now().minus(Duration.ofMinutes(5)));
        GoogleCredentials credentials = provider.forAccount(token);

        credentials.refreshIfExpired();

        assertThat(credentials.getAccessToken().getTokenValue()).isEqualTo("fresh-access");
        assertThat(tokenRequests).singleElement().asString()
                .contains("refresh_token=stored-refresh").contains("client_id=client-id");
        ArgumentCaptor<String> encrypted = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Instant> expiry = ArgumentCaptor.forClass(Instant.class);
        verify(tokens).updateAccessToken(eq(token.getId()), encrypted.capture(), expiry.capture(), any());
        assertThat(encrypted.getValue()).isNotEqualTo("fresh-access");
        assertThat(encryption.decrypt(encrypted.getValue())).isEqualTo("fresh-access");
        assertThat(expiry.getValue()).isBetween(Instant.now().plus(Duration.ofMinutes(55)),
                Instant.now().plus(Duration.ofMinutes(61)));
    }
}
