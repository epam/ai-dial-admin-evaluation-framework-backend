package com.epam.aidial.evaluation.configuration.security;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.util.AuthorizationTokenHolder;
import com.epam.aidial.evaluation.runner.util.CallerCredential;
import com.epam.aidial.evaluation.runner.util.CredentialKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

@DisplayName("AuthorizationHeaderInterceptor")
class AuthorizationHeaderInterceptorTest {

    private final AuthorizationHeaderInterceptor interceptor = new AuthorizationHeaderInterceptor();

    @AfterEach
    void tearDown() {
        AuthorizationTokenHolder.clearToken();
    }

    @Test
    @DisplayName("captures a bearer credential from the Authorization header")
    void capturesBearerCredential() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer abc123");

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(AuthorizationTokenHolder.getCredential())
                .isEqualTo(new CallerCredential("abc123", CredentialKind.BEARER));
    }

    @Test
    @DisplayName("captures an API-key credential when Authorization is absent")
    void capturesApiKeyCredentialWhenAuthorizationAbsent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CallerCredential.API_KEY_HEADER, "my-key");

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(AuthorizationTokenHolder.getCredential())
                .isEqualTo(new CallerCredential("my-key", CredentialKind.API_KEY));
    }

    @Test
    @DisplayName("captures the bearer credential when both Authorization: Bearer and Api-Key are present")
    void capturesBearerCredentialWhenBothHeadersPresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer abc123");
        request.addHeader(CallerCredential.API_KEY_HEADER, "my-key");

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(AuthorizationTokenHolder.getCredential())
                .isEqualTo(new CallerCredential("abc123", CredentialKind.BEARER));
    }

    @Test
    @DisplayName("captures nothing when Authorization is non-blank but not Bearer, even with Api-Key present")
    void capturesNothingWhenAuthorizationIsNonBearer() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Basic dXNlcjpwYXNz");
        request.addHeader(CallerCredential.API_KEY_HEADER, "my-key");

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(AuthorizationTokenHolder.getCredential()).isNull();
    }

    @Test
    @DisplayName("captures nothing when neither header is present")
    void capturesNothingWhenNeitherHeaderPresent() {
        MockHttpServletRequest request = new MockHttpServletRequest();

        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(AuthorizationTokenHolder.getCredential()).isNull();
    }

    @Test
    @DisplayName("clears the credential on afterCompletion")
    void clearsCredentialOnAfterCompletion() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(HttpHeaders.AUTHORIZATION, "Bearer abc123");
        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        interceptor.afterCompletion(request, new MockHttpServletResponse(), new Object(), null);

        assertThat(AuthorizationTokenHolder.getCredential()).isNull();
    }

    @Test
    @DisplayName("clears the credential on afterConcurrentHandlingStarted")
    void clearsCredentialOnAfterConcurrentHandlingStarted() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader(CallerCredential.API_KEY_HEADER, "my-key");
        interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        interceptor.afterConcurrentHandlingStarted(request, new MockHttpServletResponse(), new Object());

        assertThat(AuthorizationTokenHolder.getCredential()).isNull();
    }
}
