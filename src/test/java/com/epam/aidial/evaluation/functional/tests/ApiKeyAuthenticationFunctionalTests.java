package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.data.db.repository.TestSuiteRepository;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.web.security.apikey.CoreApiKeyIntrospector;
import com.epam.aidial.evaluation.web.security.apikey.IntrospectionResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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

    @Autowired
    private TestSuiteRepository testSuiteRepository;

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

    @Test
    @DisplayName(
            "POST /test-suites created by an Api-Key caller persists createdBy = the introspected project principal")
    void createdEntityIsAttributedToApiKeyPrincipal() {
        when(coreApiKeyIntrospector.introspect("valid-key"))
                .thenReturn(new IntrospectionResult("my-project", List.of("admin"), true));

        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name("Api-Key-Attribution-" + UUID.randomUUID())
                .deploymentRef(
                        DeploymentReferenceDto.builder().id("d1").name("D1").build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .requestBodySchema(JsonRequestBodySchemaDto.builder()
                                .schema(Map.of("type", "object", "properties", Map.of()))
                                .build())
                        .build())
                .build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("Api-Key", "valid-key");
        ResponseEntity<TestSuiteResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites"), new HttpEntity<>(request, headers), TestSuiteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        UUID suiteId = response.getBody().getId();
        assertThat(testSuiteRepository.findById(suiteId).orElseThrow().getCreatedBy())
                .isEqualTo("my-project");
    }

    private ResponseEntity<String> exchangeWithApiKey(String apiKey) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Api-Key", apiKey);
        HttpEntity<Void> request = new HttpEntity<>(headers);
        return restTemplate.exchange(apiUrl(PROTECTED_PATH), HttpMethod.GET, request, String.class);
    }
}
