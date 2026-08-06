package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
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
import com.epam.aidial.evaluation.runner.dto.SchemaFieldType;
import com.epam.aidial.evaluation.runner.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.runner.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
