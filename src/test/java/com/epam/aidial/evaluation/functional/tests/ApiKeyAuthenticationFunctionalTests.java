package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.web.security.apikey.CoreApiKeyIntrospector;
import com.epam.aidial.evaluation.web.security.apikey.IntrospectionResult;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Verifies the DIAL API-Key authentication filter is wired into the OIDC security chain
 * correctly. DIAL Core introspection itself is exhaustively covered by
 * {@code CoreApiKeyIntrospectorTest}; here {@link CoreApiKeyIntrospector} is mocked so these
 * tests focus purely on the filter's place in the chain.
 */
@DisplayName("Startup smoke tests (security mode: oidc, api-key enabled)")
public abstract class ApiKeyAuthenticationFunctionalTests extends BaseFunctionalTest {

    private static final String PROTECTED_PATH = "/metric-declarations?page=0&size=1";

    @MockitoBean
    private CoreApiKeyIntrospector coreApiKeyIntrospector;

    @BeforeEach
    void resetMocks() {
        reset(coreApiKeyIntrospector);
    }

    @Test
    @DisplayName("Should authenticate a valid project-key")
    void shouldAuthenticateValidApiKey() {
        when(coreApiKeyIntrospector.introspect("valid-key"))
                .thenReturn(new IntrospectionResult("my-project", List.of("admin"), true));

        ResponseEntity<String> response = exchangeWithApiKey("valid-key");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Should reject an invalid API key with 401")
    void shouldRejectInvalidApiKey() {
        when(coreApiKeyIntrospector.introspect("bad-key")).thenThrow(new BadCredentialsException("Invalid API key"));

        ResponseEntity<String> response = exchangeWithApiKey("bad-key");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Should return 503 when DIAL Core is unreachable")
    void shouldReturn503WhenCoreUnreachable() {
        when(coreApiKeyIntrospector.introspect("any-key"))
                .thenThrow(new AuthenticationServiceException("DIAL Core unreachable"));

        ResponseEntity<String> response = exchangeWithApiKey("any-key");

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
    }

    @Test
    @DisplayName("Should ignore Api-Key header when Authorization header is present")
    void shouldIgnoreApiKeyWhenAuthorizationPresent() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("malformed.token.value");
        headers.set("Api-Key", "valid-key");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<String> response =
                restTemplate.exchange(apiUrl(PROTECTED_PATH), HttpMethod.GET, request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        verifyNoInteractions(coreApiKeyIntrospector);
    }

    private ResponseEntity<String> exchangeWithApiKey(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Api-Key", apiKey);
        HttpEntity<Void> request = new HttpEntity<>(headers);
        return restTemplate.exchange(apiUrl(PROTECTED_PATH), HttpMethod.GET, request, String.class);
    }
}
