package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.client.dialcore.DialCoreDeploymentInvoker;
import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.data.db.model.RunStatus;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.service.domain.dto.ChainRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.DeploymentReferenceDto;
import com.epam.aidial.evaluation.service.domain.dto.HttpChainRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.InputBindingDto;
import com.epam.aidial.evaluation.service.domain.dto.JsonRequestBodyDto;
import com.epam.aidial.evaluation.service.domain.dto.RequestTemplateDto;
import com.epam.aidial.evaluation.service.domain.dto.ResponseColumnDefinitionDto;
import com.epam.aidial.evaluation.service.domain.dto.RunConfigDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunResponseDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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

/**
 * End-to-end execution of a multi-request chain: one result row per executed request, cross-request
 * {@code responseField} resolution through the accumulating column map, per-request extracted columns,
 * fail-fast abort, and snapshot immutability against later suite edits.
 */
@DisplayName("Multi-Request Execution Functional Tests")
public abstract class MultiRequestExecutionFunctionalTests extends AbstractMultiRequestFunctionalTest {

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private DialCoreDeploymentInvoker dialCoreDeploymentInvoker;

    @BeforeEach
    void setUp() {
        reset(dialCoreDeploymentInvoker);
        analyticsTestDataHelper.cleanupResults();
    }

    @Test
    @DisplayName("a three-request chain writes one row per request, in chain order with its label")
    void chainWritesOneRowPerRequest() {
        stubPerPathResponses();
        TestSuiteResponseDto suite = createChainSuite(3);

        UUID runId = runToCompletion(suite.getId());

        List<Map<String, Object>> rows = analyticsTestDataHelper.findResultsByRunId(runId);
        assertThat(rows).hasSize(3);
        assertThat(rows)
                .extracting(row -> row.get("request_index"), row -> row.get("request_label"))
                .containsExactlyInAnyOrder(tuple(0, "setup"), tuple(1, "invoke"), tuple(2, "measure"));
        // Multi-request and multi-turn are mutually exclusive, so every chain row is turn 0 of 1.
        assertThat(rows).allSatisfy(row -> {
            assertThat(row.get("turn_index")).isEqualTo(0);
            assertThat(row.get("total_turns")).isEqualTo(1);
            assertThat(row.get("execution_status")).isEqualTo(ExecutionStatus.SUCCESS.name());
        });
    }

    @Test
    @DisplayName("a later request resolves a responseField from an EARLIER request's extracted column")
    void laterRequestConsumesEarlierExtractedColumn() {
        stubPerPathResponses();
        TestSuiteResponseDto suite = createChainSuite(3);

        UUID runId = runToCompletion(suite.getId());

        // Request 2 ('measure') binds to request 0's `session_id`, not its immediate predecessor's column —
        // this is what the accumulating map exists for.
        String measureBody = String.valueOf(rowAt(runId, 2).get("request_body"));
        assertThat(measureBody).contains("sess-1");
    }

    @Test
    @DisplayName("no message history is carried between chain requests — each body comes only from its template")
    void noMessageHistoryBetweenRequests() {
        stubPerPathResponses();
        TestSuiteResponseDto suite = createChainSuite(3);

        UUID runId = runToCompletion(suite.getId());

        String invokeBody = String.valueOf(rowAt(runId, 1).get("request_body"));
        // Request 1's body holds exactly its own resolved variable. Neither request 0's prompt nor its
        // response text appears: "chain" means data flow through declared bindings, not conversation.
        assertThat(invokeBody).contains("sess-1").doesNotContain("messages").doesNotContain("hello there");
    }

    @Test
    @DisplayName("each row carries only its OWN request's extracted columns, so a chain's rows are sparse")
    void rowsCarryOnlyTheirOwnExtractedColumns() {
        stubPerPathResponses();
        TestSuiteResponseDto suite = createChainSuite(3);

        UUID runId = runToCompletion(suite.getId());

        assertThat(String.valueOf(rowAt(runId, 0).get("extracted_columns")))
                .contains("session_id")
                .doesNotContain("answer");
        assertThat(String.valueOf(rowAt(runId, 1).get("extracted_columns")))
                .contains("answer")
                .doesNotContain("session_id");
        assertThat(String.valueOf(rowAt(runId, 2).get("extracted_columns")))
                .contains("latency")
                .doesNotContain("answer");
    }

    @Test
    @DisplayName("a failing request aborts the chain — its row is written and later requests are never sent")
    void failingRequestAbortsChain() {
        // 400 is non-retryable, so the abort is observed without waiting out a retry schedule.
        when(dialCoreDeploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    String path = invocation.getArgument(1);
                    if (path.contains("/v1/setup")) {
                        return okResult(Map.of("value", "sess-1"));
                    }
                    if (path.contains("/v1/invoke")) {
                        return new DeploymentInvocationResult(
                                400, false, Map.of("error", "bad request"), null, new HttpHeaders());
                    }
                    throw new AssertionError("Request after the failing one must never be sent, but got " + path);
                });
        TestSuiteResponseDto suite = createChainSuite(3);

        UUID runId = runToCompletion(suite.getId());

        List<Map<String, Object>> rows = analyticsTestDataHelper.findResultsByRunId(runId);
        assertThat(rows).hasSize(2);
        assertThat(rowAt(runId, 0).get("execution_status")).isEqualTo(ExecutionStatus.SUCCESS.name());
        assertThat(rowAt(runId, 1).get("execution_status")).isEqualTo(ExecutionStatus.FAILED.name());
        assertThat(rows).noneSatisfy(row -> assertThat(row.get("request_index")).isEqualTo(2));
    }

    @Test
    @DisplayName("a run's frozen chain survives a later suite edit — the old run stays 3 requests, the new one is 4")
    void frozenChainSurvivesSuiteEdit() {
        stubPerPathResponses();
        TestSuiteResponseDto suite = createChainSuite(3);

        UUID firstRunId = runToCompletion(suite.getId());
        assertThat(analyticsTestDataHelper.findResultsByRunId(firstRunId)).hasSize(3);

        // Grow the suite to a 4-request chain AFTER the first run was snapshotted.
        ResponseEntity<String> update = updateChainSuite(suite, 4);
        assertThat(update.getStatusCode()).isEqualTo(HttpStatus.OK);

        UUID secondRunId = runToCompletion(suite.getId());

        assertThat(analyticsTestDataHelper.findResultsByRunId(secondRunId))
                .as("the new run executes the edited 4-request chain")
                .hasSize(4);
        assertThat(analyticsTestDataHelper.findResultsByRunId(firstRunId))
                .as("the earlier run's rows are unaffected by the edit")
                .hasSize(3);

        // The frozen snapshot still describes the 3-request chain it was created against.
        ResponseEntity<TestSuiteRunResponseDto> firstRun =
                restTemplate.getForEntity(apiUrl("/test-suite-runs/" + firstRunId), TestSuiteRunResponseDto.class);
        assertThat(firstRun.getBody().getSuiteSnapshot().getAdditionalRequests())
                .hasSize(2);
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    /** Each chain path returns a distinct `value`, so extraction per request is unambiguous. */
    private void stubPerPathResponses() {
        when(dialCoreDeploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenAnswer(invocation -> {
                    String path = invocation.getArgument(1);
                    if (path.contains("/v1/setup")) {
                        return okResult(Map.of("value", "sess-1"));
                    }
                    if (path.contains("/v1/invoke")) {
                        return okResult(Map.of("value", "hello there"));
                    }
                    return okResult(Map.of("value", "42"));
                });
    }

    private static DeploymentInvocationResult okResult(Map<String, Object> body) {
        return new DeploymentInvocationResult(200, false, body, null, new HttpHeaders());
    }

    private Map<String, Object> rowAt(UUID runId, int requestIndex) {
        return analyticsTestDataHelper.findResultsByRunId(runId).stream()
                .filter(row -> Integer.valueOf(requestIndex).equals(row.get("request_index")))
                .findFirst()
                .orElseThrow(() -> new AssertionError("No row for request index " + requestIndex));
    }

    private UUID runToCompletion(UUID suiteId) {
        ResponseEntity<TestSuiteRunResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suiteId + "/runs"),
                jsonEntity(TestSuiteRunRequestDto.builder()
                        .runConfig(RunConfigDto.builder().numberOfRuns(1).build())
                        .build()),
                TestSuiteRunResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);
        UUID runId = response.getBody().getId();
        awaitRunTerminal(runId, 30);
        return runId;
    }

    private void awaitRunTerminal(UUID runId, int timeoutSeconds) {
        long deadline = System.currentTimeMillis() + TimeUnit.SECONDS.toMillis(timeoutSeconds);
        while (System.currentTimeMillis() < deadline) {
            ResponseEntity<TestSuiteRunResponseDto> get =
                    restTemplate.getForEntity(apiUrl("/test-suite-runs/" + runId), TestSuiteRunResponseDto.class);
            if (get.getStatusCode() == HttpStatus.OK
                    && get.getBody() != null
                    && RunStatus.isTerminal(get.getBody().getStatus())) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted while polling run status", e);
            }
        }
        throw new AssertionError("Run did not reach a terminal status within " + timeoutSeconds + " seconds");
    }

    /**
     * A chain of {@code requestCount} requests over a dataset holding one runnable single-turn case.
     * Request 0 extracts {@code session_id}; request 1 consumes it and extracts {@code answer}; request 2
     * consumes request 0's column again (not its predecessor's), proving the accumulating map spans the chain.
     */
    private TestSuiteResponseDto createChainSuite(int requestCount) {
        UUID datasetId = datasetWithPromptField("MRExec-", false);
        createSingleTurnCase(datasetId, "st-case", Map.of("prompt", "ask something"));
        ResponseEntity<TestSuiteResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites"),
                jsonEntity(chainSuiteRequest("MRExec-Suite-" + UUID.randomUUID(), datasetId, requestCount)),
                TestSuiteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().isValid()).isTrue();
        return response.getBody();
    }

    private ResponseEntity<String> updateChainSuite(TestSuiteResponseDto suite, int requestCount) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setIfMatch("\"" + (suite.getVersion() != null ? suite.getVersion() : 0L) + "\"");
        return restTemplate.exchange(
                apiUrl("/test-suites/" + suite.getId()),
                HttpMethod.PUT,
                new HttpEntity<>(chainSuiteRequest(suite.getName(), suite.getDatasetId(), requestCount), headers),
                String.class);
    }

    private TestSuiteRequestDto chainSuiteRequest(String name, UUID datasetId, int requestCount) {
        List<ChainRequestDto> extra = new ArrayList<>();
        if (requestCount > 1) {
            extra.add(element(
                    "invoke",
                    "/v1/invoke",
                    Map.of("sid", "${{sid}}"),
                    List.of(InputBindingDto.builder()
                            .templateVariable("sid")
                            .responseField("session_id")
                            .build()),
                    List.of(responseColumn("answer", "value"))));
        }
        if (requestCount > 2) {
            extra.add(element(
                    "measure",
                    "/v1/measure",
                    Map.of("sid", "${{sid}}"),
                    List.of(InputBindingDto.builder()
                            .templateVariable("sid")
                            .responseField("session_id")
                            .build()),
                    List.of(responseColumn("latency", "value"))));
        }
        for (int i = 3; i < requestCount; i++) {
            extra.add(element(
                    "extra-" + i,
                    "/v1/extra" + i,
                    Map.of("p", "constant"),
                    List.of(),
                    List.of(responseColumn("extra" + i, "value"))));
        }

        return TestSuiteRequestDto.builder()
                .name(name)
                .datasetId(datasetId)
                .deploymentRef(DeploymentReferenceDto.builder()
                        .id("deployment-1")
                        .name("Deployment One")
                        .version("v1")
                        .build())
                .requestLabel("setup")
                .endpointRef(endpoint("/v1/setup"))
                .requestTemplate(RequestTemplateDto.builder()
                        .urlTemplate("/v1/setup")
                        .body(JsonRequestBodyDto.builder()
                                .content(Map.of("p", "${{p}}"))
                                .build())
                        .build())
                .inputBindings(List.of(InputBindingDto.builder()
                        .templateVariable("p")
                        .dataField("prompt")
                        .build()))
                .responseColumns(List.of(responseColumn("session_id", "value")))
                .additionalRequests(extra.isEmpty() ? null : extra)
                .build();
    }

    /** The shared chain element plus a JSON body, which these execution tests need and the base does not. */
    private static HttpChainRequestDto element(
            String label,
            String path,
            Map<String, Object> bodyContent,
            List<InputBindingDto> bindings,
            List<ResponseColumnDefinitionDto> responseColumns) {
        HttpChainRequestDto element = chainElement(label, path, bindings, responseColumns);
        element.setRequestTemplate(RequestTemplateDto.builder()
                .urlTemplate(path)
                .body(JsonRequestBodyDto.builder().content(bodyContent).build())
                .build());
        return element;
    }
}
