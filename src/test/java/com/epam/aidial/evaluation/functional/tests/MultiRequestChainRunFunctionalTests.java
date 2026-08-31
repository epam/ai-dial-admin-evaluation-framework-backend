package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.metricprovider.MetricProviderClient;
import com.epam.aidial.evaluation.client.metricprovider.dto.EvaluationRequestDto;
import com.epam.aidial.evaluation.client.metricprovider.dto.EvaluationResponseDto;
import com.epam.aidial.evaluation.client.metricprovider.dto.MetricOutputFieldDto;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.functional.helper.MetricDeclarationTestDataProvider;
import com.epam.aidial.evaluation.runner.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.runner.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.runner.dto.EndpointContractDto;
import com.epam.aidial.evaluation.runner.dto.FieldDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.InputBindingDto;
import com.epam.aidial.evaluation.runner.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.runner.dto.RequestDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.runner.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.runner.dto.RunConfigDto;
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryExportRequestDto;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;

/**
 * End-to-end functional coverage for section 5 (runner chain executor and turn-loop generalization) of the
 * {@code add-multi-request-suite} change: a 2-request chain — a non-multi-turn "configure" request #0
 * followed by a multi-turn "ask" additional request — asserting per-row {@code request_index}/{@code
 * total_requests}/{@code turn_index}/{@code total_turns}, the accumulated {@code extracted_columns} on
 * each row, chain abort when request #0 fails, and that a legacy single-request suite's rows carry the
 * untouched {@code request_index=0}/{@code total_requests=1} defaults.
 */
@DisplayName("Multi-request chain run — end-to-end execution")
public abstract class MultiRequestChainRunFunctionalTests extends AbstractMultiTurnFunctionalTest {

    @Autowired
    private MetricProviderClient metricProviderClient;

    @Autowired
    private MetricDeclarationTestDataProvider metricDeclarationTestDataProvider;

    private TestSuiteResponseDto createChainSuite(String name) {
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name(name + " " + UUID.randomUUID())
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/configure")
                        .build())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("prompt")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .perTurn(true)
                        .build())))
                .requestName("configure")
                .requestTemplate(RequestTemplateDto.builder()
                        .urlTemplate("/v1/configure")
                        .body(JsonRequestBodyDto.builder()
                                .content(Map.of("op", "configure"))
                                .build())
                        .build())
                .inputBindings(List.of())
                .responseColumns(List.of(ResponseColumnDefinitionDto.builder()
                        .name("configId")
                        .expression("usage.total_tokens")
                        .type(SchemaFieldType.INTEGER)
                        .build()))
                .additionalRequests(List.of(RequestDefinitionDto.builder()
                        .name("ask")
                        .endpointRef(EndpointContractDto.builder()
                                .method(HttpMethod.POST)
                                .relativeUrlPattern("/v1/ask")
                                .build())
                        .requestTemplate(RequestTemplateDto.builder()
                                .urlTemplate("/v1/ask")
                                .body(JsonRequestBodyDto.builder()
                                        .jsonataContent("{\"cfg\": $configId, \"messages\": "
                                                + "[{\"role\": \"user\", \"content\": \"${{prompt}}\"}]}")
                                        .build())
                                .build())
                        .inputBindings(List.of(InputBindingDto.builder()
                                .templateVariable("prompt")
                                .dataField("prompt")
                                .build()))
                        .responseColumns(List.of(ResponseColumnDefinitionDto.builder()
                                .name("answer")
                                .expression("choices[0].message.content")
                                .type(SchemaFieldType.STRING)
                                .build()))
                        .build()))
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    private DeploymentInvocationResult configureReply(int totalTokens) {
        return new DeploymentInvocationResult(
                200, false, Map.of("usage", Map.of("total_tokens", totalTokens)), null, new HttpHeaders());
    }

    /**
     * A 2-request chain whose "ask" request's body reads the "configure" request's inline-evaluated
     * "Accuracy" TSMD output via {@code $_metrics} — the substring {@code InlineModeDetector} scans for,
     * making every run of this suite inline. Used by section 4 (group 4) of the {@code
     * inline-metric-evaluation} change's coverage.
     */
    private TestSuiteResponseDto createInlineMetricChainSuite(String name) {
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name(name + " " + UUID.randomUUID())
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/configure")
                        .build())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("prompt")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build())))
                .requestName("configure")
                .requestTemplate(RequestTemplateDto.builder()
                        .urlTemplate("/v1/configure")
                        .body(JsonRequestBodyDto.builder()
                                .content(Map.of("op", "configure"))
                                .build())
                        .build())
                .inputBindings(List.of())
                .responseColumns(List.of(ResponseColumnDefinitionDto.builder()
                        .name("configId")
                        .expression("usage.total_tokens")
                        .type(SchemaFieldType.INTEGER)
                        .build()))
                .additionalRequests(List.of(RequestDefinitionDto.builder()
                        .name("ask")
                        .endpointRef(EndpointContractDto.builder()
                                .method(HttpMethod.POST)
                                .relativeUrlPattern("/v1/ask")
                                .build())
                        .requestTemplate(RequestTemplateDto.builder()
                                .urlTemplate("/v1/ask")
                                .body(JsonRequestBodyDto.builder()
                                        .jsonataContent("{\"cfg\": $configId, \"metricScore\": "
                                                + "$_metrics.Accuracy.score.value, \"messages\": "
                                                + "[{\"role\": \"user\", \"content\": \"${{prompt}}\"}]}")
                                        .build())
                                .build())
                        .inputBindings(List.of(InputBindingDto.builder()
                                .templateVariable("prompt")
                                .dataField("prompt")
                                .build()))
                        .responseColumns(List.of(ResponseColumnDefinitionDto.builder()
                                .name("answer")
                                .expression("choices[0].message.content")
                                .type(SchemaFieldType.STRING)
                                .build()))
                        .build()))
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        TestSuiteResponseDto suite = response.getBody();
        assertThat(suite).isNotNull();

        metricDeclarationTestDataProvider.insertSeedMetricDeclarations();
        metricDeclarationTestDataProvider.insertSeedVersionForAccuracy();
        UUID declarationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID versionId = UUID.fromString("770e8400-e29b-41d4-a716-446655440001");
        metaTestDataHelper.createTestSuiteMetricDefinition(
                suite.getId(),
                declarationId,
                versionId,
                "Accuracy",
                "[]",
                "[{\"property\":\"actual\",\"source\":{\"$type\":\"Constant\",\"value\":\"x\"}}]");

        return suite;
    }

    private EvaluationResponseDto accuracyResponse(double score) {
        return EvaluationResponseDto.builder()
                .metricName("Accuracy")
                .output(Map.of(
                        "score",
                        MetricOutputFieldDto.builder()
                                .type("value")
                                .value(BigDecimal.valueOf(score))
                                .build()))
                .build();
    }

    @Test
    @DisplayName("Inline chain: request #1's body reads request #0's inline-evaluated metric output via"
            + " $_metrics; one eval summary per row sharing the run's computationId, a run_metric_snapshots"
            + " row present, and the provider dispatched exactly once per row (no redundant Phase-2 round)")
    void inlineChain_requestReadsPriorMetricOutput_oneSummaryPerRowNoPhase2Round() {
        TestSuiteResponseDto suite = createInlineMetricChainSuite("Inline metric chain");
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        createSingleTurnCase(datasetId, "inline-chain-case", Map.of("prompt", "hi"));

        AtomicInteger call = new AtomicInteger();
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    int callIndex = call.getAndIncrement();
                    if (callIndex == 0) {
                        return configureReply(7);
                    }
                    return chatReply("Mocked answer.");
                });
        when(metricProviderClient.evaluate(anyString(), any(EvaluationRequestDto.class)))
                .thenReturn(accuracyResponse(0.75));

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 30);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        assertThat(results).hasSize(2);
        assertThat(results)
                .allSatisfy(r ->
                        assertThat(String.valueOf(r.get("execution_status"))).isEqualTo("SUCCESS"));

        // Request #1's own body evaluation reads request #0's inline-evaluated Accuracy output.
        Map<String, Object> request1Row = results.stream()
                .filter(r -> ((Number) r.get("request_index")).intValue() == 1)
                .findFirst()
                .orElseThrow();
        assertThat(String.valueOf(request1Row.get("request_body"))).contains("0.75");

        List<Map<String, Object>> summaries = analyticsTestDataHelper.findEvalSummariesByRunId(run.getId());
        assertThat(summaries).hasSize(2);
        Object sharedComputationId = summaries.get(0).get("computation_id");
        assertThat(sharedComputationId).isNotNull();
        assertThat(summaries).extracting(s -> s.get("computation_id")).containsOnly(sharedComputationId);

        List<Map<String, Object>> snapshots = analyticsTestDataHelper.findRunMetricSnapshotsByRunId(run.getId());
        assertThat(snapshots).hasSize(1);

        // Exactly once per row (2 rows total) — never doubled by a redundant Phase-2 pass over the same
        // already-inline-scored SUCCESS rows.
        verify(metricProviderClient, times(2)).evaluate(anyString(), any(EvaluationRequestDto.class));
    }

    @Test
    @DisplayName("Inline chain: a failing metric mock aborts request #1 while request #0's row stays SUCCESS"
            + " and its own eval summary is FAILED")
    void inlineChain_failingMetricAbortsChain_request0RowStaysSuccess() {
        TestSuiteResponseDto suite = createInlineMetricChainSuite("Inline metric chain abort");
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        createSingleTurnCase(datasetId, "inline-chain-abort-case", Map.of("prompt", "hi"));

        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(configureReply(7));
        when(metricProviderClient.evaluate(anyString(), any(EvaluationRequestDto.class)))
                .thenThrow(new RuntimeException("metric provider unavailable"));

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 30);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        // Request #1 never executes — the deployment invoker is called exactly once, for request #0.
        verify(deploymentInvoker, times(1)).invokeWithStreaming(any(), any(), any(), any(), any());

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        assertThat(results).hasSize(1);
        Map<String, Object> row = results.getFirst();
        assertThat(((Number) row.get("request_index")).intValue()).isZero();
        assertThat(String.valueOf(row.get("execution_status")))
                .as("the deployment call itself succeeded, so the row's own executionStatus stays SUCCESS"
                        + " even though its metric failed")
                .isEqualTo("SUCCESS");

        List<Map<String, Object>> summaries = analyticsTestDataHelper.findEvalSummariesByRunId(run.getId());
        assertThat(summaries).hasSize(1);
        assertThat(String.valueOf(summaries.getFirst().get("execution_status"))).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("Inline run cancelled mid-Phase-1 leaves rows, eval summaries, and the run_metric_snapshots"
            + " row consistent with the CANCELLED run status")
    void inlineChain_cancellationMidPhase1_leavesStateConsistent() throws InterruptedException {
        TestSuiteResponseDto suite = createInlineMetricChainSuite("Inline metric chain cancel");
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        for (int i = 0; i < 5; i++) {
            createSingleTurnCase(datasetId, "inline-cancel-case-" + i, Map.of("prompt", "hi " + i));
        }

        AtomicInteger call = new AtomicInteger();
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    try {
                        Thread.sleep(300);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                    return call.getAndIncrement() % 2 == 0 ? configureReply(7) : chatReply("Mocked answer.");
                });
        when(metricProviderClient.evaluate(anyString(), any(EvaluationRequestDto.class)))
                .thenReturn(accuracyResponse(0.5));

        ResponseEntity<TestSuiteRunResponseDto> started = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/runs"),
                jsonEntity(TestSuiteRunRequestDto.builder()
                        .runConfig(RunConfigDto.builder().numberOfRuns(1).build())
                        .build()),
                TestSuiteRunResponseDto.class);
        assertThat(started.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID runId = started.getBody().getId();

        Thread.sleep(150);
        restTemplate.postForEntity(
                apiUrl("/test-suite-runs/" + runId + "/cancel"), null, TestSuiteRunResponseDto.class);

        TestSuiteRunResponseDto terminal = awaitRunTerminal(runId, 30);
        assertThat(terminal.getStatus()).isEqualTo(RunStatus.CANCELLED.name());

        // The snapshot write is unconditional before Phase 1 starts, so it is present regardless of how
        // far Phase 1 got before cancellation was observed.
        List<Map<String, Object>> snapshots = analyticsTestDataHelper.findRunMetricSnapshotsByRunId(runId);
        assertThat(snapshots).hasSize(1);

        // Every row produced before cancellation has exactly one eval summary (inline-written for SUCCESS
        // rows), so the two tables stay mutually consistent under cancellation.
        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(runId);
        List<Map<String, Object>> summaries = analyticsTestDataHelper.findEvalSummariesByRunId(runId);
        assertThat(summaries).hasSameSizeAs(results);
    }

    private EvaluationResponseDto accuracyResponseWithDetails(double score, String reason) {
        return EvaluationResponseDto.builder()
                .metricName("Accuracy")
                .output(Map.of(
                        "score",
                        MetricOutputFieldDto.builder()
                                .type("value")
                                .value(BigDecimal.valueOf(score))
                                .details(Map.of("reason", reason))
                                .build()))
                .build();
    }

    /**
     * {@link #createInlineMetricChainSuite(String)} plus a second TSMD, "DetailsReader", conditioned on
     * {@code request.last} (dispatched only on the "ask" request's row) with two {@code Expression}
     * input bindings reading the "Accuracy" TSMD's inline-evaluated output accumulated from request #0
     * via {@code $_metrics}: one reads the {@code details.reason} string, the other the {@code value}
     * number. Used by section 5 (group 5) of the {@code inline-metric-evaluation} change's coverage.
     */
    private TestSuiteResponseDto createInlineMetricChainSuiteWithExpressionBinding(String name) {
        TestSuiteResponseDto suite = createInlineMetricChainSuite(name);
        UUID declarationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID versionId = UUID.fromString("770e8400-e29b-41d4-a716-446655440001");
        String inputBindings = """
                [
                    {"property": "reason", "source": {"$type": "Expression", "expression": "$_metrics.Accuracy.score.details.reason"}},
                    {"property": "scoreValue", "source": {"$type": "Expression", "expression": "$_metrics.Accuracy.score.value"}}
                ]
                """;
        metaTestDataHelper.createTestSuiteMetricDefinition(
                suite.getId(), declarationId, versionId, "DetailsReader", "[]", inputBindings.trim(), "request.last");
        return suite;
    }

    /**
     * {@link #createInlineMetricChainSuite(String)} plus a second, unconditioned TSMD, "BadRef", whose
     * single {@code Expression} input binding references a TSMD name that never produces a {@code
     * $_metrics} entry — always {@code undefined}, even on request #0's own row. Used to prove an
     * unresolved {@code Expression} reference fails synchronously (before any provider call) and aborts
     * the inline chain, rather than silently resolving to {@code null}.
     */
    private TestSuiteResponseDto createInlineMetricChainSuiteWithUndefinedExpressionBinding(String name) {
        TestSuiteResponseDto suite = createInlineMetricChainSuite(name);
        UUID declarationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID versionId = UUID.fromString("770e8400-e29b-41d4-a716-446655440001");
        String inputBindings = """
                [{"property": "missing", "source": {"$type": "Expression", "expression": "$_metrics.NeverRan.score.value"}}]
                """;
        metaTestDataHelper.createTestSuiteMetricDefinition(
                suite.getId(), declarationId, versionId, "BadRef", "[]", inputBindings.trim());
        return suite;
    }

    @Test
    @DisplayName("Inline chain: an Expression-bound input on request #1's TSMD reads request #0's"
            + " inline-evaluated metric's details.reason string and value number via $_metrics; CSV export's"
            + " metric::/metricInfo:: columns are unaffected by the new binding type")
    void inlineChain_expressionBindingReadsPriorMetricDetailsAndValue_csvExportUnaffected() {
        TestSuiteResponseDto suite = createInlineMetricChainSuiteWithExpressionBinding("Inline expression binding");
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        createSingleTurnCase(datasetId, "inline-expr-case", Map.of("prompt", "hi"));

        AtomicInteger call = new AtomicInteger();
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> call.getAndIncrement() == 0 ? configureReply(7) : chatReply("Mocked answer."));

        ArgumentCaptor<EvaluationRequestDto> requestCaptor = ArgumentCaptor.forClass(EvaluationRequestDto.class);
        when(metricProviderClient.evaluate(anyString(), requestCaptor.capture()))
                .thenReturn(accuracyResponseWithDetails(0.75, "matched expected output"));

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 30);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        EvaluationRequestDto detailsReaderRequest = requestCaptor.getAllValues().stream()
                .filter(req -> req.getInput() != null && req.getInput().containsKey("reason"))
                .findFirst()
                .orElseThrow(() -> new AssertionError("DetailsReader TSMD was never dispatched"));
        assertThat(detailsReaderRequest.getInput().get("reason")).isEqualTo("matched expected output");
        Object scoreValue = detailsReaderRequest.getInput().get("scoreValue");
        assertThat(scoreValue).isInstanceOf(Number.class);
        assertThat(((Number) scoreValue).doubleValue()).isEqualTo(0.75);

        ResponseEntity<String> exportResponse = restTemplate.postForEntity(
                apiUrl("/analytics/eval-summaries/export.csv"),
                jsonEntity(
                        EvalSummaryExportRequestDto.builder().runId(run.getId()).build()),
                String.class);
        assertThat(exportResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        String header = exportResponse.getBody().lines().findFirst().orElseThrow();
        assertThat(header).contains("metric::Accuracy::score").contains("metric::DetailsReader::score");
    }

    @Test
    @DisplayName("Inline chain: an unresolved Expression reference at request #0 fails synchronously (before"
            + " any provider call) and aborts the chain — never silently null")
    void inlineChain_undefinedExpressionReferenceAbortsChain() {
        TestSuiteResponseDto suite =
                createInlineMetricChainSuiteWithUndefinedExpressionBinding("Inline undefined expression");
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        createSingleTurnCase(datasetId, "inline-undefined-expr-case", Map.of("prompt", "hi"));

        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(configureReply(7));
        when(metricProviderClient.evaluate(anyString(), any(EvaluationRequestDto.class)))
                .thenReturn(accuracyResponse(0.5));

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 30);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        // Request #1 never executes — the chain aborted after request #0.
        verify(deploymentInvoker, times(1)).invokeWithStreaming(any(), any(), any(), any(), any());
        // Exactly one provider call for request #0's row — from Accuracy only; BadRef's Expression
        // binding throws before ever reaching the metric provider client, proving the failure is a hard
        // synchronous fail-fast, not a silently-resolved null passed on to the provider.
        verify(metricProviderClient, times(1)).evaluate(anyString(), any(EvaluationRequestDto.class));

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        assertThat(results).hasSize(1);
        assertThat(String.valueOf(results.getFirst().get("execution_status")))
                .as("the deployment call itself succeeded, so the row's own executionStatus stays SUCCESS"
                        + " even though BadRef's binding failed")
                .isEqualTo("SUCCESS");

        List<Map<String, Object>> summaries = analyticsTestDataHelper.findEvalSummariesByRunId(run.getId());
        assertThat(summaries).hasSize(1);
        assertThat(String.valueOf(summaries.getFirst().get("execution_status"))).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("A malformed Expression binding on a multi-request suite's TSMD is rejected with HTTP 400"
            + " at write time")
    void expressionBindingMalformedOnMultiRequestSuite_rejectedWith400() {
        TestSuiteResponseDto suite = createChainSuite("Chain malformed expression");
        metricDeclarationTestDataProvider.insertSeedMetricDeclarations();
        metricDeclarationTestDataProvider.insertSeedVersionForAccuracy();

        String requestJson = """
                {
                    "name": "Malformed Expression On Chain Suite",
                    "metricDeclarationId": "00000000-0000-0000-0000-000000000001",
                    "metricDeclarationVersionId": "770e8400-e29b-41d4-a716-446655440001",
                    "configBindings": [],
                    "inputBindings": [
                        {"property": "reason", "source": {"$type": "Expression", "expression": "this is (not valid"}}
                    ]
                }
                """;

        ResponseEntity<String> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/metric-definitions"), jsonEntity(requestJson), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("VALIDATION_ERROR");
    }

    /**
     * A single-turn 2-request chain (no multi-turn dataset field needed): request #0 "configure" produces
     * {@code configId}; the additional "ask" request produces {@code answer}. Used by section 6's
     * condition + metric evaluation coverage — {@link #conditionRequestLast_producesMetricValuesOnlyOnFinalRequest()}.
     */
    private TestSuiteResponseDto createConditionalMetricChainSuite(String name) {
        TestSuiteRequestDto request = TestSuiteRequestDto.builder()
                .name(name + " " + UUID.randomUUID())
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .endpointRef(EndpointContractDto.builder()
                        .method(HttpMethod.POST)
                        .relativeUrlPattern("/v1/configure")
                        .build())
                .datasetId(newDatasetWithSchema(List.of(FieldDefinitionDto.builder()
                        .name("prompt")
                        .type(SchemaFieldType.STRING)
                        .required(true)
                        .build())))
                .requestName("configure")
                .requestTemplate(RequestTemplateDto.builder()
                        .urlTemplate("/v1/configure")
                        .body(JsonRequestBodyDto.builder()
                                .content(Map.of("op", "configure"))
                                .build())
                        .build())
                .inputBindings(List.of())
                .responseColumns(List.of(ResponseColumnDefinitionDto.builder()
                        .name("configId")
                        .expression("usage.total_tokens")
                        .type(SchemaFieldType.INTEGER)
                        .build()))
                .additionalRequests(List.of(RequestDefinitionDto.builder()
                        .name("ask")
                        .endpointRef(EndpointContractDto.builder()
                                .method(HttpMethod.POST)
                                .relativeUrlPattern("/v1/ask")
                                .build())
                        .requestTemplate(RequestTemplateDto.builder()
                                .urlTemplate("/v1/ask")
                                .body(JsonRequestBodyDto.builder()
                                        .jsonataContent("{\"cfg\": $configId, \"messages\": "
                                                + "[{\"role\": \"user\", \"content\": \"${{prompt}}\"}]}")
                                        .build())
                                .build())
                        .inputBindings(List.of(InputBindingDto.builder()
                                .templateVariable("prompt")
                                .dataField("prompt")
                                .build()))
                        .responseColumns(List.of(ResponseColumnDefinitionDto.builder()
                                .name("answer")
                                .expression("choices[0].message.content")
                                .type(SchemaFieldType.STRING)
                                .build()))
                        .build()))
                .build();

        ResponseEntity<TestSuiteResponseDto> response =
                restTemplate.postForEntity(apiUrl("/test-suites"), jsonEntity(request), TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response.getBody();
    }

    @Test
    @DisplayName("A TSMD conditioned on request.last produces metric values only on the final request's"
            + " eval-summary rows; earlier rows stay SUCCESS and metric-free")
    void conditionRequestLast_producesMetricValuesOnlyOnFinalRequest() {
        TestSuiteResponseDto suite = createConditionalMetricChainSuite("Chain conditional metric");
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        createSingleTurnCase(datasetId, "chain-condition-case", Map.of("prompt", "hi"));

        metricDeclarationTestDataProvider.insertSeedMetricDeclarations();
        metricDeclarationTestDataProvider.insertSeedVersionForAccuracy();
        UUID declarationId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        UUID versionId = UUID.fromString("770e8400-e29b-41d4-a716-446655440001");

        // The binding references "answer", a column only the final ("ask") request produces. Without
        // the "request.last" condition this would resolve to missing on request #0's row; the condition
        // skips the metric on that row entirely instead (design.md D21).
        String inputBindings = """
                [{"property": "actual", "source": {"$type": "Response", "columnName": "answer"}}]
                """;
        metaTestDataHelper.createTestSuiteMetricDefinition(
                suite.getId(), declarationId, versionId, "Accuracy", "[]", inputBindings.trim(), "request.last");

        AtomicInteger call = new AtomicInteger();
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    int callIndex = call.getAndIncrement();
                    if (callIndex == 0) {
                        return configureReply(7);
                    }
                    return chatReply("Mocked answer.");
                });
        when(metricProviderClient.evaluate(anyString(), any(EvaluationRequestDto.class)))
                .thenReturn(EvaluationResponseDto.builder()
                        .metricName("Accuracy")
                        .output(Map.of(
                                "Accuracy",
                                MetricOutputFieldDto.builder()
                                        .type("value")
                                        .value(BigDecimal.ONE)
                                        .build()))
                        .build());

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 30);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        assertThat(results).hasSize(2);
        assertThat(results)
                .allSatisfy(r ->
                        assertThat(String.valueOf(r.get("execution_status"))).isEqualTo("SUCCESS"));

        List<Map<String, Object>> summaries = analyticsTestDataHelper.findEvalSummariesByRunId(run.getId());
        assertThat(summaries).hasSize(2);
        assertThat(summaries)
                .allSatisfy(s ->
                        assertThat(String.valueOf(s.get("execution_status"))).isEqualTo("SUCCESS"));

        Map<String, Object> firstRequestSummary = summaries.stream()
                .filter(s -> ((Number) s.get("request_index")).intValue() == 0)
                .findFirst()
                .orElseThrow();
        assertThat((String) firstRequestSummary.get("metric_values")).isEqualTo("{}");

        Map<String, Object> lastRequestSummary = summaries.stream()
                .filter(s -> ((Number) s.get("request_index")).intValue() == 1)
                .findFirst()
                .orElseThrow();
        assertThat((String) lastRequestSummary.get("metric_values")).contains("Accuracy");
    }

    @Test
    @DisplayName("2-request chain: request #0 (single-turn) then request #1 (2 turns) persists 3 rows with"
            + " correct request/turn dimensions and accumulated extracted_columns")
    void twoRequestChain_persistsRowsWithRequestAndTurnDimensions() {
        TestSuiteResponseDto suite = createChainSuite("Chain 2-request");
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        createMultiTurnCase(datasetId, "chain-case", List.of(Map.of("prompt", "q0"), Map.of("prompt", "q1")));

        AtomicInteger call = new AtomicInteger();
        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenAnswer(inv -> {
                    int callIndex = call.getAndIncrement();
                    if (callIndex == 0) {
                        return configureReply(7);
                    }
                    return chatReply("reply-" + (callIndex - 1));
                });

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 30);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        assertThat(results).hasSize(3);
        assertThat(results)
                .allSatisfy(r ->
                        assertThat(String.valueOf(r.get("execution_status"))).isEqualTo("SUCCESS"));
        assertThat(results)
                .allSatisfy(r -> assertThat(((Number) r.get("total_requests")).intValue())
                        .isEqualTo(2));

        Map<String, Object> request0Row = results.stream()
                .filter(r -> ((Number) r.get("request_index")).intValue() == 0)
                .findFirst()
                .orElseThrow();
        assertThat(((Number) request0Row.get("turn_index")).intValue()).isZero();
        assertThat(((Number) request0Row.get("total_turns")).intValue()).isEqualTo(1);
        // Request #0's own extracted_columns must not carry a later request's column — the accumulated
        // union only grows forward along the chain, never backfilling an earlier request's row.
        assertThat(extractedColumns(request0Row)).containsEntry("configId", 7).doesNotContainKey("answer");

        List<Map<String, Object>> request1Rows = results.stream()
                .filter(r -> ((Number) r.get("request_index")).intValue() == 1)
                .toList();
        assertThat(request1Rows).hasSize(2);
        assertThat(request1Rows.stream().map(r -> ((Number) r.get("turn_index")).intValue()))
                .containsExactlyInAnyOrder(0, 1);
        assertThat(request1Rows)
                .allSatisfy(r ->
                        assertThat(((Number) r.get("total_turns")).intValue()).isEqualTo(2));
        // Every request-#1 row's extracted_columns is the accumulated union: request #0's configId plus
        // this request's own answer column (Decision 4 — accumulated union serialization).
        assertThat(request1Rows).allSatisfy(r -> assertThat(extractedColumns(r)).containsEntry("configId", 7));
        Map<String, Object> turn0 = request1Rows.stream()
                .filter(r -> ((Number) r.get("turn_index")).intValue() == 0)
                .findFirst()
                .orElseThrow();
        Map<String, Object> turn1 = request1Rows.stream()
                .filter(r -> ((Number) r.get("turn_index")).intValue() == 1)
                .findFirst()
                .orElseThrow();
        assertThat(extractedColumns(turn0)).containsEntry("answer", "reply-0");
        assertThat(extractedColumns(turn1)).containsEntry("answer", "reply-1");
        // Request #1's turn 1 body still sees request #0's accumulated $configId frame variable.
        assertThat(String.valueOf(turn1.get("request_body"))).contains("7").contains("q1");
    }

    /** Parses a result row's {@code extracted_columns} JSONB text into a plain map for value assertions —
     * avoids brittle raw-string matching against Postgres' own JSONB text-canonicalization whitespace. */
    private Map<String, Object> extractedColumns(Map<String, Object> row) {
        try {
            return objectMapper.readValue(
                    String.valueOf(row.get("extracted_columns")), new TypeReference<Map<String, Object>>() {});
        } catch (JacksonException e) {
            throw new IllegalStateException("Failed to parse extracted_columns fixture", e);
        }
    }

    @Test
    @DisplayName("Chain abort: a failing request #0 persists exactly one row and request #1 is never invoked")
    void chainAbort_stopsAtFailingFirstRequest() {
        TestSuiteResponseDto suite = createChainSuite("Chain abort");
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        createMultiTurnCase(datasetId, "chain-abort-case", List.of(Map.of("prompt", "q0")));

        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(
                        new DeploymentInvocationResult(500, false, Map.of("error", "boom"), null, new HttpHeaders()));

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 30);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        assertThat(results).hasSize(1);
        Map<String, Object> row = results.getFirst();
        assertThat(((Number) row.get("request_index")).intValue()).isZero();
        assertThat(((Number) row.get("total_requests")).intValue()).isEqualTo(2);
        assertThat(String.valueOf(row.get("execution_status"))).isEqualTo("FAILED");
    }

    @Test
    @DisplayName("Legacy single-request suite: rows carry request_index=0/total_requests=1 with no stamping")
    void legacySingleRequestSuite_leavesRequestDimensionAtDefaults() {
        TestSuiteResponseDto suite = createChatSuite("Chain legacy single-request");
        UUID datasetId = metaTestDataHelper.getDatasetId(suite.getId());
        createSingleTurnCase(datasetId, "legacy-case", Map.of("prompt", "hi"));

        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(chatReply("hello"));

        TestSuiteRunResponseDto run = createRunAndAwaitTerminal(suite.getId(), 30);
        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED.name());

        List<Map<String, Object>> results = analyticsTestDataHelper.findResultsByRunId(run.getId());
        assertThat(results).hasSize(1);
        Map<String, Object> row = results.getFirst();
        assertThat(((Number) row.get("request_index")).intValue()).isZero();
        assertThat(((Number) row.get("total_requests")).intValue()).isEqualTo(1);
        assertThat(((Number) row.get("turn_index")).intValue()).isZero();
        assertThat(((Number) row.get("total_turns")).intValue()).isEqualTo(1);
    }
}
