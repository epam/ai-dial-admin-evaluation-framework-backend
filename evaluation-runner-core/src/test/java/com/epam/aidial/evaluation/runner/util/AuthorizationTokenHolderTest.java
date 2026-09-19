package com.epam.aidial.evaluation.runner.util;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

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

        assertThat(AuthorizationTokenHolder.getCredential())
                .isEqualTo(new CallerCredential("abc123", CredentialKind.BEARER));
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

        assertThat(AuthorizationTokenHolder.getCredential())
                .isEqualTo(new CallerCredential("second", CredentialKind.BEARER));
    }

    @Test
    @DisplayName("setCredential stores an API-key credential")
    void setCredentialStoresApiKeyCredential() {
        AuthorizationTokenHolder.setCredential(CallerCredential.apiKey("my-key"));

        assertThat(AuthorizationTokenHolder.getCredential())
                .isEqualTo(new CallerCredential("my-key", CredentialKind.API_KEY));
    }

    @Test
    @DisplayName("setCredential with null clears the stored credential")
    void setCredentialWithNullClears() {
        AuthorizationTokenHolder.setCredential(CallerCredential.bearer("abc123"));
        AuthorizationTokenHolder.setCredential(null);

        assertThat(AuthorizationTokenHolder.getCredential()).isNull();
    }

    @Test
    @DisplayName("CallerCredential.bearer/apiKey return null for blank or null input")
    void factoriesReturnNullForBlankOrNullInput() {
        assertThat(CallerCredential.bearer(null)).isNull();
        assertThat(CallerCredential.bearer("  ")).isNull();
        assertThat(CallerCredential.apiKey(null)).isNull();
        assertThat(CallerCredential.apiKey("  ")).isNull();
    }

    @Test
    @DisplayName("CallerCredential.toString masks the raw value")
    void callerCredentialToStringMasksValue() {
        String rendered = CallerCredential.apiKey("super-secret-key").toString();

        assertThat(rendered).doesNotContain("super-secret-key").isEqualTo("CallerCredential[kind=API_KEY, value=***]");
    }

    @Test
    @DisplayName("headerName returns Authorization for a bearer credential and Api-Key for an API-key credential")
    void headerNameSelectsHeaderByKind() {
        assertThat(CallerCredential.bearer("abc123").headerName()).isEqualTo(HttpHeaders.AUTHORIZATION);
        assertThat(CallerCredential.apiKey("abc123").headerName()).isEqualTo(CallerCredential.API_KEY_HEADER);
    }

    @Test
    @DisplayName(
            "headerValue prefixes Bearer for a bearer credential and passes the raw value for an API-key credential")
    void headerValueFormatsValueByKind() {
        assertThat(CallerCredential.bearer("abc123").headerValue()).isEqualTo("Bearer abc123");
        assertThat(CallerCredential.apiKey("abc123").headerValue()).isEqualTo("abc123");
    }
}
