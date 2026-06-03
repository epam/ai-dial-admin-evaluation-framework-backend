package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.client.dialcore.DialCoreDeploymentInvoker;
import com.epam.aidial.evaluation.data.db.model.Dataset;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.service.domain.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.EndpointContractDto;
import com.epam.aidial.evaluation.service.domain.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.JsonRequestBodySchemaDto;
import com.epam.aidial.evaluation.service.domain.dto.ParameterDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.ParameterLocation;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.RunConfigDto;
import com.epam.aidial.evaluation.service.domain.dto.SchemaFieldType;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunResponseDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/**
 * Functional tests for the failure modes fixed by `fix-lost-test-case-results-on-error`:
 * <ul>
 *   <li>Per-case worker exception → run COMPLETED with a synthetic ERROR row preserved.</li>
 *   <li>Mid-flight cancellation → run CANCELLED with absent rows for unfinished cases
 *       (no synthetic CANCELLED rows).</li>
 * </ul>
 *
 * <p>The third scenario from the change spec — a dispatch-loop catastrophe (e.g., DB closed
 * mid-page) — is intentionally omitted here: replicating it functionally would require
 * destabilizing the meta DB connection mid-run, which is too fragile per review feedback.
 * That path is covered by the unit test
 * {@code InProcessEvaluationExecutorTest#shouldRethrow_whenDispatchLoopFails}.
 */
@DisplayName("Evaluation Executor Failure Modes Functional Tests")
public abstract class EvaluationExecutorFailureModesFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private DialCoreDeploymentInvoker deploymentInvoker;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID newDatasetWithSchema(List<FieldDefinitionDto> schema) {
        try {
            String schemaJson = objectMapper.writeValueAsString(schema);
            Dataset dataset = metaTestDataHelper.createDataset("eefm-" + UUID.randomUUID(), schemaJson);
            return dataset.getId();
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize testCaseSchema fixture", e);
        }
    }

    @Test
    @DisplayName("Should complete run with ERROR row preserved when one worker invocation throws")
    void shouldCompleteRunWithErrorRow_whenWorkerInvocationThrows() {
        TestSuiteResponseDto suite = createTestSuite("Suite Worker Throws");
        createTestCaseForSuite(suite.getId(), "TC1", Map.of("expected", "a"));
        createTestCaseForSuite(suite.getId(), "TC2", Map.of("expected", "b"));

        AtomicInteger callCount = new AtomicInteger();
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    if (callCount.getAndIncrement() == 0) {
                        throw new RuntimeException("simulated worker boom");
                    }
                    return new DeploymentInvocationResult(
                            200,
                            false,
                            Map.of("id", "mock", "choices", List.of(Map.of("message", Map.of("content", "answer")))),
                            null,
                            new HttpHeaders());
                });

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 30);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        // Functional-level guarantee: the run completes with one row per case,
        // and the failed case produces an ERROR row (no rows are silently lost).
        // Note: at the functional level, EvaluationWorker's invocation-error path catches the
        // RuntimeException from the deployment invoker and writes a real ERROR row with
        // envelope {"error":{"code":"INVOCATION_ERROR",...}}. The executor's *synthetic* ERROR
        // path (envelope {"error":{"type":"RuntimeException","origin":"executor",...}}) only
        // fires when the worker itself throws, which requires worker-level mocking and is
        // exercised by InProcessEvaluationExecutorTest#shouldSynthesizeErrorRow_whenWorkerThrows.
        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        assertThat(results).hasSize(2);

        long errorCount = results.stream()
                .filter(r -> "ERROR".equals(String.valueOf(r.get("execution_status"))))
                .count();
        long successCount = results.stream()
                .filter(r -> "SUCCESS".equals(String.valueOf(r.get("execution_status"))))
                .count();
        assertThat(errorCount).isEqualTo(1);
        assertThat(successCount).isEqualTo(1);

        Map<String, Object> errorRow = results.stream()
                .filter(r -> "ERROR".equals(String.valueOf(r.get("execution_status"))))
                .findFirst()
                .orElseThrow();
        String responseBody = String.valueOf(errorRow.get("response_body"));
        // Either the worker-level INVOCATION_ERROR envelope or the executor-level synthetic
        // envelope is acceptable here; both preserve the failure as a non-silent ERROR row.
        assertThat(responseBody).contains("simulated worker boom");
    }

    @Test
    @DisplayName("Should cancel run mid-flight with rows for unfinished cases absent (no synthetic CANCELLED rows)")
    void shouldCancelRunMidFlight_withAbsentRowsForUnfinishedCases() {
        TestSuiteResponseDto suite = createTestSuite("Suite Cancel Mid-flight");
        for (int i = 1; i <= 4; i++) {
            createTestCaseForSuite(suite.getId(), "TC" + i, Map.of("expected", "v" + i));
        }

        // Slow down workers so the run is still RUNNING when we cancel
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    Thread.sleep(2000);
                    return new DeploymentInvocationResult(
                            200,
                            false,
                            Map.of("id", "mock", "choices", List.of(Map.of("message", Map.of("content", "answer")))),
                            null,
                            new HttpHeaders());
                });

        ResponseEntity<TestSuiteRunResponseDto> createResponse = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/runs"),
                jsonEntity(TestSuiteRunRequestDto.builder()
                        .runConfig(RunConfigDto.builder().numberOfRuns(1).build())
                        .build()),
                TestSuiteRunResponseDto.class);
        assertThat(createResponse.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(createResponse.getBody()).isNotNull();
        UUID runId = createResponse.getBody().getId();

        // Wait until run is RUNNING (snapshot phase done) so cancel happens mid-flight
        awaitRunStatus(runId, RunStatus.RUNNING.name(), 15);

        ResponseEntity<TestSuiteRunResponseDto> cancelResponse = restTemplate.exchange(
                apiUrl("/test-suite-runs/" + runId + "/cancel"),
                HttpMethod.POST,
                jsonEntity(null),
                TestSuiteRunResponseDto.class);
        assertThat(cancelResponse.getStatusCode().is2xxSuccessful()).isTrue();

        TestSuiteRunResponseDto terminal = awaitRunTerminal(runId, 30);
        assertThat(terminal.getStatus()).isEqualTo(RunStatus.CANCELLED.name());

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(runId);
        // Strictly fewer than the total (4 cases × 1 run); some cases were interrupted before completion
        assertThat(results.size()).isLessThan(4);
        // Existing rows must be real outcomes only — never a synthetic "CANCELLED" status (the enum
        // doesn't even include CANCELLED, but assert explicitly for clarity).
        assertThat(results).allSatisfy(row -> {
            String status = String.valueOf(row.get("execution_status"));
            assertThat(status).isIn("SUCCESS", "ERROR", "FAILED", "TIMEOUT");
            assertThat(status).isNotEqualTo("CANCELLED");
        });
    }

    // --- Helper methods ---

    private TestSuiteRunResponseDto createRunAndAwaitTerminal(UUID suiteId, int timeoutSeconds) {
        ResponseEntity<TestSuiteRunResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suiteId + "/runs"),
                jsonEntity(TestSuiteRunRequestDto.builder()
                        .runConfig(RunConfigDto.builder().numberOfRuns(1).build())
                        .build()),
                TestSuiteRunResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        assertThat(response.getBody()).isNotNull();
        return awaitRunTerminal(response.getBody().getId(), timeoutSeconds);
    }

    private TestSuiteRunResponseDto awaitRunTerminal(UUID runId, int timeoutSeconds) {
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

    private void awaitRunStatus(UUID runId, String targetStatus, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<TestSuiteRunResponseDto> get =
                    restTemplate.getForEntity(apiUrl("/test-suite-runs/" + runId), TestSuiteRunResponseDto.class);
            if (get.getStatusCode() == HttpStatus.OK
                    && get.getBody() != null
                    && targetStatus.equals(get.getBody().getStatus())) {
                return;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while polling run", e);
            }
        }
        throw new AssertionError("Run did not reach status " + targetStatus + " within " + timeoutSeconds + "s");
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
                                .name("q")
                                .in(ParameterLocation.QUERY)
                                .required(false)
                                .schema(Map.of("type", "string"))
                                .build()))
                        .requestBodySchema(JsonRequestBodySchemaDto.builder()
                                .schema(Map.of("type", "object", "properties", Map.of()))
                                .build())
                        .build())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("expected")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build())))
                .requestTemplate(
                        RequestTemplateDto.builder().urlTemplate("/v1/chat").build())
                .responseColumns(List.of(ResponseColumnDefinitionDto.builder()
                        .name("answer")
                        .expression("choices[0].message.content")
                        .type(SchemaFieldType.STRING)
                        .build()))
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private TestCaseResponseDto createTestCaseForSuite(UUID suiteId, String name, Map<String, Object> data) {
        ResponseEntity<TestCaseResponseDto> response = restTemplate.postForEntity(
                apiUrl("/datasets/" + metaTestDataHelper.getDatasetId(suiteId) + "/test-cases"),
                jsonEntity(TestCaseRequestDto.builder()
                        .testCaseName(name)
                        .data(data)
                        .build()),
                TestCaseResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }
}
