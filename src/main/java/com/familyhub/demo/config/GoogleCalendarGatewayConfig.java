package com.familyhub.demo.config;

import com.familyhub.demo.google.EncryptedTokenCredentialsProvider;
import com.familyhub.demo.google.FakeGoogleCalendarGateway;
import com.familyhub.demo.google.GoogleApiCalendarGateway;
import com.familyhub.demo.google.GoogleApiExceptionTranslator;
import com.familyhub.demo.google.GoogleCalendarGateway;
import com.familyhub.demo.google.GoogleCredentialsProvider;
import com.familyhub.demo.repository.GoogleOAuthTokenRepository;
import com.familyhub.demo.service.TokenEncryptionService;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.http.HttpTransport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.io.IOException;
import java.security.GeneralSecurityException;

/**
 * Wires the Google Calendar gateway (Phase 4): the real one, or the in-memory fake when
 * {@code google.calendar.fake} is set (the dev profile, for the e2e suite).
 */
@Slf4j
@Configuration
public class GoogleCalendarGatewayConfig {

    /**
     * The HTTP transport Google calls and token refreshes go over.
     *
     * @return a transport trusting Google's certificates
     * @throws GeneralSecurityException if the trust store cannot be loaded
     * @throws IOException              if the trust store cannot be read
     */
    @Bean
    public HttpTransport googleHttpTransport() throws GeneralSecurityException, IOException {
        return GoogleNetHttpTransport.newTrustedTransport();
    }

    /**
     * Credentials from members' stored sign-ins.
     *
     * @param encryption decrypts and encrypts stored tokens
     * @param oauth      the OAuth client
     * @param tokens     where refreshed tokens are stored
     * @param transport  the HTTP transport
     * @return the provider
     */
    @Bean
    public GoogleCredentialsProvider googleCredentialsProvider(TokenEncryptionService encryption,
                                                               GoogleOAuthConfig oauth,
                                                               GoogleOAuthTokenRepository tokens,
                                                               HttpTransport transport) {
        return new EncryptedTokenCredentialsProvider(encryption, oauth, tokens, transport);
    }

    /**
     * The gateway the sync and the write path use.
     *
     * @param properties  whether to fake Google, and the timeouts
     * @param credentials credentials per account
     * @param transport   the HTTP transport
     * @param translator  failure translation
     * @return the real gateway, or the fake when configured
     */
    @Bean
    public GoogleCalendarGateway googleCalendarGateway(GoogleCalendarProperties properties,
                                                       GoogleCredentialsProvider credentials,
                                                       HttpTransport transport,
                                                       GoogleApiExceptionTranslator translator) {
        if (properties.isFake()) {
            log.warn("google.calendar.fake is set: calendar events live in memory, not in Google");
            return new FakeGoogleCalendarGateway();
        }
        return new GoogleApiCalendarGateway(credentials, transport, properties, translator);
    }
}
