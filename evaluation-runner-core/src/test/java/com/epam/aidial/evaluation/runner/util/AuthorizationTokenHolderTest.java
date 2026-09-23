package com.epam.aidial.evaluation.runner.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("AuthorizationTokenHolder")
class AuthorizationTokenHolderTest {

    @AfterEach
    void tearDown() {
        AuthorizationTokenHolder.clearToken();
    }

    @Test
    @DisplayName("getCredential returns null when not set")
    void getCredentialReturnsNullWhenNotSet() {
        assertThat(AuthorizationTokenHolder.getCredential()).isNull();
    }

    @Test
    @DisplayName("getCredential returns the value set via setCredential")
    void getCredentialReturnsValueAfterSet() {
        AuthorizationTokenHolder.setCredential(CallerCredential.bearer("abc123"));

        assertThat(AuthorizationTokenHolder.getCredential()).isEqualTo(CallerCredential.bearer("abc123"));
    }

    @Test
    @DisplayName("clearToken removes stored credential")
    void clearTokenRemovesStoredCredential() {
        AuthorizationTokenHolder.setCredential(CallerCredential.bearer("xyz"));
        AuthorizationTokenHolder.clearToken();

        assertThat(AuthorizationTokenHolder.getCredential()).isNull();
    }

    @Test
    @DisplayName("setCredential overwrites previous value")
    void setCredentialOverwritesPrevious() {
        AuthorizationTokenHolder.setCredential(CallerCredential.bearer("first"));
        AuthorizationTokenHolder.setCredential(CallerCredential.bearer("second"));

        assertThat(AuthorizationTokenHolder.getCredential()).isEqualTo(CallerCredential.bearer("second"));
    }

    @Test
    @DisplayName("setCredential stores an API-key credential")
    void setCredentialStoresApiKeyCredential() {
        AuthorizationTokenHolder.setCredential(CallerCredential.apiKey("my-key"));

        assertThat(AuthorizationTokenHolder.getCredential()).isEqualTo(CallerCredential.apiKey("my-key"));
    }

    @Test
    @DisplayName("setCredential with null clears the stored credential")
    void setCredentialWithNullClears() {
        AuthorizationTokenHolder.setCredential(CallerCredential.bearer("abc123"));
        AuthorizationTokenHolder.setCredential(null);

        assertThat(AuthorizationTokenHolder.getCredential()).isNull();
    }
}
