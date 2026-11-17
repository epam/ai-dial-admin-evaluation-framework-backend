package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.runner.config.properties.EvaluationRunProperties;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunConfigResponseDto;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@DisplayName("Test Suite Run Config Functional Tests")
public abstract class TestSuiteRunConfigFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private EvaluationRunProperties evaluationRunProperties;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Should return configured maxConcurrencyLevel without resolving 'config' as a run id")
    void shouldReturnConfiguredMaxConcurrencyLevel() {
        final ResponseEntity<TestSuiteRunConfigResponseDto> response =
                restTemplate.getForEntity(apiUrl("/test-suite-runs/config"), TestSuiteRunConfigResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getMaxConcurrencyLevel())
                .isEqualTo(evaluationRunProperties.getExecution().getMaxConcurrencyLevel());
    }

    @Test
    @DisplayName("Should publish the minimal OpenAPI example for GET /api/v1/test-suite-runs/config")
    void shouldPublishOpenApiExample() {
        final ResponseEntity<String> apiDocs = restTemplate.getForEntity(baseUrl() + "/v3/api-docs", String.class);
        assertThat(apiDocs.getStatusCode()).isEqualTo(HttpStatus.OK);

        final JsonNode examples = objectMapper
                .readTree(apiDocs.getBody())
                .path("paths")
                .path("/api/v1/test-suite-runs/config")
                .path("get")
                .path("responses")
                .path("200")
                .path("content")
                .path("application/json")
                .path("examples");

        assertThat(examples.propertyNames()).contains("minimal");
        assertThat(examples.path("minimal").path("value").path("maxConcurrencyLevel").asInt())
                .isEqualTo(5);
    }
}
