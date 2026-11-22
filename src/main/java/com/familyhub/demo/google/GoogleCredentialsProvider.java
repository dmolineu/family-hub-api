package com.familyhub.demo.google;

import com.familyhub.demo.model.GoogleOAuthToken;
import com.google.auth.oauth2.GoogleCredentials;

/** Credentials to call Google as one signed-in account. */
@FunctionalInterface
public interface GoogleCredentialsProvider {

    /**
     * Credentials for an account, refreshing themselves when the access token expires.
     *
     * @param account the stored, encrypted sign-in
     * @return credentials to authorise requests with
     */
    GoogleCredentials forAccount(GoogleOAuthToken account);
}
