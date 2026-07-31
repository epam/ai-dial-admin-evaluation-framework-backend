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
    @DisplayName("getToken returns null when not set")
    void getTokenReturnsNullWhenNotSet() {
        assertThat(AuthorizationTokenHolder.getToken()).isNull();
    }

    @Test
    @DisplayName("getToken returns value after setToken")
    void getTokenReturnsValueAfterSet() {
        AuthorizationTokenHolder.setToken("Bearer abc123");
        assertThat(AuthorizationTokenHolder.getToken()).isEqualTo("Bearer abc123");
    }

    @Test
    @DisplayName("clearToken removes stored token")
    void clearTokenRemovesStoredToken() {
        AuthorizationTokenHolder.setToken("Bearer xyz");
        AuthorizationTokenHolder.clearToken();
        assertThat(AuthorizationTokenHolder.getToken()).isNull();
    }

    @Test
    @DisplayName("setToken overwrites previous value")
    void setTokenOverwritesPrevious() {
        AuthorizationTokenHolder.setToken("first");
        AuthorizationTokenHolder.setToken("second");
        assertThat(AuthorizationTokenHolder.getToken()).isEqualTo("second");
    }
}
