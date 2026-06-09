package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@DisplayName("Startup smoke tests (security mode: oidc)")
public abstract class OidcSecurityStartupSmokeTest extends BaseFunctionalTest {

    @Test
    @DisplayName("Should expose OpenAPI and public health endpoints")
    void shouldExposeOpenApiAndHealth() {
        ResponseEntity<String> openApi = restTemplate.getForEntity(baseUrl() + "/v3/api-docs", String.class);
        assertThat(openApi.getStatusCode()).isEqualTo(HttpStatus.OK);

        // SpringDoc returns the spec as byte[]; jacksonJsonHttpMessageConverter declines byte[]
        // so ByteArrayHttpMessageConverter writes the raw JSON object verbatim (no Base64 wrap).
        String body = openApi.getBody();
        assertThat(body).isNotNull().startsWith("{");
        assertThat(body).as("OpenAPI spec should contain openapi version field").contains("\"openapi\"");

        ResponseEntity<String> health = restTemplate.getForEntity(apiUrl("/health"), String.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Should reject protected API access without token")
    void shouldRejectProtectedApiWithoutToken() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(apiUrl("/metric-declarations?page=0&size=1"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("Should reject malformed bearer token on protected API")
    void shouldRejectMalformedToken() {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth("malformed.token.value");
        HttpEntity<Void> request = new HttpEntity<>(headers);

        ResponseEntity<String> response =
                restTemplate.exchange(apiUrl("/metric-declarations"), HttpMethod.GET, request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
