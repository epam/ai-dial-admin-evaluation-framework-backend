package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.service.domain.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.EndpointContractDto;
import com.epam.aidial.evaluation.service.domain.dto.ExecutionSettingsDto;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.service.domain.dto.ParameterDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.ParameterLocation;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.RetryPolicyDto;
import com.epam.aidial.evaluation.service.domain.dto.RunConfigDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunResponseDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

@DisplayName("Execution Settings Functional Tests")
public abstract class ExecutionSettingsFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private ObjectMapper objectMapper;

    private java.util.UUID newDatasetWithSchema(List<FieldDefinitionDto> schema) {
        try {
            String schemaJson = objectMapper.writeValueAsString(schema);
            Dataset dataset = metaTestDataHelper.createDataset("exec-" + java.util.UUID.randomUUID(), schemaJson);
            return dataset.getId();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize testCaseSchema fixture", e);
        }
    }

    @Test
    @DisplayName("Should create run with execution settings persisted in runConfig")
    void shouldCreateRunWithExecutionSettings() {
        TestSuiteResponseDto suite = createTestSuite("Suite Exec Settings");

        RunConfigDto runConfig = RunConfigDto.builder()
                .numberOfRuns(1)
                .execution(ExecutionSettingsDto.builder()
                        .concurrencyLevel(5)
                        .requestTimeoutMs(60000L)
                        .rateLimitRps(2.5)
                        .build())
                .retry(RetryPolicyDto.builder()
                        .maxRetries(3)
                        .retryDelayMs(2000L)
                        .retryBackoffMultiplier(2.0)
                        .build())
                .build();

        ResponseEntity<TestSuiteRunResponseDto> createResponse = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/runs"),
                jsonEntity(TestSuiteRunRequestDto.builder().runConfig(runConfig).build()),
                TestSuiteRunResponseDto.class);

        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(createResponse.getBody()).isNotNull();

        // Wait for run to reach terminal state
        TestSuiteRunResponseDto terminalRun =
                awaitRunTerminal(createResponse.getBody().getId(), 15);

        // GET the run back and verify execution settings are persisted
        ResponseEntity<TestSuiteRunResponseDto> getResponse = restTemplate.getForEntity(
                apiUrl("/test-suite-runs/" + terminalRun.getId()), TestSuiteRunResponseDto.class);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody()).isNotNull();

        RunConfigDto persistedConfig = getResponse.getBody().getRunConfig();
        assertThat(persistedConfig).isNotNull();
        assertThat(persistedConfig.getNumberOfRuns()).isEqualTo(1);

        assertThat(persistedConfig.getExecution()).isNotNull();
        assertThat(persistedConfig.getExecution().getConcurrencyLevel()).isEqualTo(5);
        assertThat(persistedConfig.getExecution().getRequestTimeoutMs()).isEqualTo(60000L);
        assertThat(persistedConfig.getExecution().getRateLimitRps()).isEqualTo(2.5);

        assertThat(persistedConfig.getRetry()).isNotNull();
        assertThat(persistedConfig.getRetry().getMaxRetries()).isEqualTo(3);
        assertThat(persistedConfig.getRetry().getRetryDelayMs()).isEqualTo(2000L);
        assertThat(persistedConfig.getRetry().getRetryBackoffMultiplier()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("Should reject run with concurrencyLevel exceeding max")
    void shouldRejectRunWithConcurrencyExceedingMax() {
        TestSuiteResponseDto suite = createTestSuite("Suite Concurrency Max");

        RunConfigDto runConfig = RunConfigDto.builder()
                .numberOfRuns(1)
                .execution(ExecutionSettingsDto.builder().concurrencyLevel(999).build())
                .build();

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/runs"),
                jsonEntity(TestSuiteRunRequestDto.builder().runConfig(runConfig).build()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should reject run with requestTimeoutMs exceeding max")
    void shouldRejectRunWithTimeoutExceedingMax() {
        TestSuiteResponseDto suite = createTestSuite("Suite Timeout Max");

        RunConfigDto runConfig = RunConfigDto.builder()
                .numberOfRuns(1)
                .execution(ExecutionSettingsDto.builder()
                        .requestTimeoutMs(999999999L)
                        .build())
                .build();

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/runs"),
                jsonEntity(TestSuiteRunRequestDto.builder().runConfig(runConfig).build()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should reject run with maxRetries exceeding max")
    void shouldRejectRunWithRetriesExceedingMax() {
        TestSuiteResponseDto suite = createTestSuite("Suite Retries Max");

        RunConfigDto runConfig = RunConfigDto.builder()
                .numberOfRuns(1)
                .retry(RetryPolicyDto.builder().maxRetries(999).build())
                .build();

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/runs"),
                jsonEntity(TestSuiteRunRequestDto.builder().runConfig(runConfig).build()),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should create run with minimal config (just numberOfRuns)")
    void shouldCreateRunWithMinimalConfig() {
        TestSuiteResponseDto suite = createTestSuite("Suite Minimal Config");

        RunConfigDto runConfig = RunConfigDto.builder().numberOfRuns(1).build();

        ResponseEntity<TestSuiteRunResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/runs"),
                jsonEntity(TestSuiteRunRequestDto.builder().runConfig(runConfig).build()),
                TestSuiteRunResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getRunConfig()).isNotNull();
        assertThat(response.getBody().getRunConfig().getNumberOfRuns()).isEqualTo(1);

        awaitRunTerminal(response.getBody().getId(), 15);
    }

    // --- Helper Methods ---

    private TestSuiteRunResponseDto awaitRunTerminal(java.util.UUID runId, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<TestSuiteRunResponseDto> get =
                    restTemplate.getForEntity(apiUrl("/test-suite-runs/" + runId), TestSuiteRunResponseDto.class);
            if (get.getStatusCode() == HttpStatus.OK
                    && get.getBody() != null
                    && RunStatus.isTerminal(get.getBody().getStatus())) {
                return get.getBody();
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while polling run", e);
            }
        }
        throw new AssertionError("Run did not reach terminal status within " + timeoutSeconds + "s");
    }

    private TestSuiteResponseDto createTestSuite(String name) {
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name(name)
                .description("Description for " + name)
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/chat")
                        .parameters(List.of(ParameterDefinitionDto.builder()
                                .name("query")
                                .in(ParameterLocation.QUERY)
                                .required(true)
                                .schema(Map.of("type", "string"))
                                .build()))
                        .requestBodySchema(JsonRequestBodySchemaDto.builder()
                                .schema(Map.of(
                                        "type", "object",
                                        "required", List.of("prompt"),
                                        "properties", Map.of("prompt", Map.of("type", "string"))))
                                .build())
                        .build())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("expected")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build())))
                .requestTemplate(
                        RequestTemplateDto.builder().urlTemplate("/v1/chat").build())
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }
}
