package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@DisplayName("Startup smoke tests (security mode: none)")
public abstract class NoSecurityStartupSmokeTest extends BaseFunctionalTest {

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

        // Verify SpringDoc customizers are working (OpenApiQueryParamCustomizer injects filter param descriptions)
        assertThat(body)
                .as("OpenAPI spec should include filter parameter (emitted by @FilterParam)")
                .contains("\"name\":\"filter\"");

        ResponseEntity<String> health = restTemplate.getForEntity(apiUrl("/health"), String.class);
        assertThat(health.getStatusCode()).isEqualTo(HttpStatus.OK);
    }

    @Test
    @DisplayName("Should allow protected API access without token")
    void shouldAllowProtectedApiWithoutToken() {
        ResponseEntity<String> response =
                restTemplate.getForEntity(apiUrl("/metric-declarations?page=0&size=1"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    }
}
