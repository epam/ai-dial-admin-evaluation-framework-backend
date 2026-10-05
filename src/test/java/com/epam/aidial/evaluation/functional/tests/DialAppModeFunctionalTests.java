package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialcore.DialRouteTriggerClient;
import com.epam.aidial.evaluation.configuration.properties.dialapp.DialAppProperties;
import com.epam.aidial.evaluation.runner.util.CallerCredential;
import com.epam.aidial.evaluation.web.security.apikey.CoreApiKeyIntrospector;
import com.epam.aidial.evaluation.web.security.apikey.IntrospectionResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Verifies DIAL App mode integration with per-request key authentication.
 * Tests both successful configuration (with API-Key enabled) and fail-fast path.
 */
@DisplayName("DIAL App mode")
public abstract class DialAppModeFunctionalTests extends BaseFunctionalTest {

    @MockitoBean
    private DialRouteTriggerClient dialRouteTriggerClient;

    @MockitoBean
    private CoreApiKeyIntrospector coreApiKeyIntrospector;

    @Autowired(required = false)
    private DialAppProperties dialAppProperties;

    @BeforeEach
    void resetMocks() {
        reset(dialRouteTriggerClient, coreApiKeyIntrospector);
    }

    @Test
    @DisplayName(
            "boots application context with dial-app-proxy.enabled=true and config.rest.security.api-key.enabled=true (functional test confirms beans wire up)")
    void bootsSuccessfullyWithBothFlagsEnabled() {
        // This test verifies the application context boots successfully when both flags are enabled.
        // If the beans don't wire up correctly, Spring would fail to start the application.
        assertThat(dialAppProperties).isNotNull();
        assertThat(dialAppProperties.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("POST /api/internal/runs/{runId}/execute returns 404 when runId does not exist (with valid API key)")
    void internalRunExecuteReturns404WhenRunNotFound() {
        UUID runId = UUID.randomUUID();

        // Set up the API key authentication mock
        when(coreApiKeyIntrospector.introspect("test-prk"))
                .thenReturn(new IntrospectionResult("test-project", List.of("user"), true));

        // Create a request with an API key header
        HttpHeaders headers = new HttpHeaders();
        headers.set("Api-Key", "test-prk");
        HttpEntity<Void> request = new HttpEntity<>((Void) null, headers);

        // Mock the DialRouteTriggerClient
        doNothing().when(dialRouteTriggerClient).triggerEvalRun(any(UUID.class), any(CallerCredential.class));

        String internalUrl = baseUrl() + "/api/internal/runs/" + runId + "/execute";
        ResponseEntity<String> response = restTemplate.exchange(internalUrl, HttpMethod.POST, request, String.class);

        // Should return 404 when the run doesn't exist
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }
}
