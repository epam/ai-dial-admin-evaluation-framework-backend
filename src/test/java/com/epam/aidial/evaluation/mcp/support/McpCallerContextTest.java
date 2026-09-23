package com.epam.aidial.evaluation.mcp.support;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.util.AuthorizationTokenHolder;
import com.epam.aidial.evaluation.runner.util.CallerCredential;
import com.epam.aidial.evaluation.runner.util.CredentialKind;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

@DisplayName("McpCallerContext")
class McpCallerContextTest {

    private final McpCallerContext callerContext = new McpCallerContext();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
        AuthorizationTokenHolder.clearToken();
    }

    @Test
    @DisplayName("jwt() returns the Jwt when the authentication principal is a Jwt")
    void jwtReturnsJwtForJwtPrincipal() {
        Jwt jwt = jwt();
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(jwt, null));

        assertThat(callerContext.jwt()).isSameAs(jwt);
        assertThat(callerContext.authentication()).isNotNull();
    }

    @Test
    @DisplayName("jwt() returns null when the authentication principal is a String (Api-Key caller)")
    void jwtReturnsNullForStringPrincipal() {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("my-project", null));

        assertThat(callerContext.jwt()).isNull();
    }

    @Test
    @DisplayName("jwt() and authentication() return null when there is no authentication")
    void jwtAndAuthenticationReturnNullWhenUnauthenticated() {
        assertThat(callerContext.authentication()).isNull();
        assertThat(callerContext.jwt()).isNull();
    }

    @Test
    @DisplayName("authentication() treats AnonymousAuthenticationToken as no real caller (none-mode default)")
    void authenticationReturnsNullForAnonymousToken() {
        SecurityContextHolder.getContext()
                .setAuthentication(new AnonymousAuthenticationToken(
                        "key", "anonymousUser", List.of(new SimpleGrantedAuthority("ROLE_ANONYMOUS"))));

        assertThat(callerContext.authentication()).isNull();
        assertThat(callerContext.jwt()).isNull();
    }

    @Test
    @DisplayName("callerCredential() returns the bearer credential captured for this thread")
    void callerCredentialReturnsBearerCredential() {
        AuthorizationTokenHolder.setCredential(CallerCredential.bearer("alice-token"));

        CallerCredential credential = callerContext.callerCredential();

        assertThat(credential.kind()).isEqualTo(CredentialKind.BEARER);
        assertThat(credential.value()).isEqualTo("alice-token");
    }

    @Test
    @DisplayName("callerCredential() returns the API-key credential captured for this thread")
    void callerCredentialReturnsApiKeyCredential() {
        AuthorizationTokenHolder.setCredential(CallerCredential.apiKey("valid-key"));

        CallerCredential credential = callerContext.callerCredential();

        assertThat(credential.kind()).isEqualTo(CredentialKind.API_KEY);
        assertThat(credential.value()).isEqualTo("valid-key");
    }

    @Test
    @DisplayName("callerCredential() returns null when no credential was captured on this thread")
    void callerCredentialReturnsNullWhenNotSet() {
        assertThat(callerContext.callerCredential()).isNull();
    }

    private static Jwt jwt() {
        Map<String, Object> claims = Map.of("sub", "alice", "roles", List.of("admin"));
        return new Jwt("token-value", Instant.now(), Instant.now().plusSeconds(60), Map.of("alg", "none"), claims);
    }
}
