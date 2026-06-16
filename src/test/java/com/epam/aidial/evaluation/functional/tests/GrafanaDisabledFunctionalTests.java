package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.epam.aidial.evaluation.client.dialcore.DeploymentInvocationResult;
import com.epam.aidial.evaluation.client.dialcore.DialCoreDeploymentInvoker;
import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.service.domain.dto.TestCaseResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TestSuiteRunResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.TryItOutResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.BatchWriteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.CursorPageResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryBatchWriteItemDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryBatchWriteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryBatchWriteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunMetricSnapshotBatchWriteItemDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunMetricSnapshotBatchWriteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.TestCaseRunResultResponseDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@DisplayName("Grafana Integration - Disabled (Default)")
public abstract class GrafanaDisabledFunctionalTests extends AbstractGrafanaFunctionalTests {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private DialCoreDeploymentInvoker deploymentInvoker;

    private UUID testSuiteId;

    @BeforeEach
    void setUp() {
        analyticsTestDataHelper.cleanupEvalSummaries();
        analyticsTestDataHelper.cleanupRunMetricSnapshots();
        analyticsTestDataHelper.cleanupResults();
        testSuiteId =
                metaTestDataHelper.createTestSuite("Grafana Disabled Suite").getId();
    }

    @Test
    @DisplayName("Should not include grafanaTraceUrl in analytics result when Grafana is not configured")
    void shouldNotIncludeGrafanaTraceUrlWhenDisabled() {
        UUID runId = metaTestDataHelper.createTestSuiteRun(testSuiteId).getId();
        insertResultWithTraceId(testSuiteId, runId, "abc123trace");

        UUID resultId = analyticsTestDataHelper.findAnyResultId().orElseThrow();

        ResponseEntity<TestCaseRunResultResponseDto> response = restTemplate.getForEntity(
                apiUrl("/analytics/test-case-results/" + resultId), TestCaseRunResultResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getExecutionInfo()).isNotNull();
        assertThat(response.getBody().getExecutionInfo().getTraceId()).isEqualTo("abc123trace");
        assertThat(response.getBody().getExecutionInfo().getGrafanaTraceUrl()).isNull();
    }

    @Test
    @DisplayName("Should not include grafanaExploreUrl in test suite run when Grafana is not configured")
    void shouldNotIncludeGrafanaExploreUrlWhenDisabled() {
        TestSuiteRun run = metaTestDataHelper.createCompletedRunWithTimestamps(testSuiteId);

        ResponseEntity<TestSuiteRunResponseDto> response =
                restTemplate.getForEntity(apiUrl("/test-suite-runs/" + run.getId()), TestSuiteRunResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getStartedAt()).isNotNull();
        assertThat(response.getBody().getCompletedAt()).isNotNull();
        assertThat(response.getBody().getGrafanaExploreUrl()).isNull();
    }

    @Test
    @DisplayName("Should not include grafanaTraceUrl in try-it-out response when Grafana is not configured")
    void shouldNotIncludeGrafanaTraceUrlInTryItOutWhenDisabled() {
        TestSuiteResponseDto suite = createSuiteWithTemplate();
        TestCaseResponseDto tc = createTestCase(suite.getId(), "grafana-disabled-tc", Map.of("promptField", "Hello"));

        when(deploymentInvoker.invokeWithStreaming(any(), any(), any(), any(), any()))
                .thenReturn(new DeploymentInvocationResult(
                        200, false, Map.of("choices", List.of()), null, new HttpHeaders()));

        ResponseEntity<TryItOutResponseDto> response = restTemplate.postForEntity(
                apiUrl("/test-suites/" + suite.getId() + "/test-cases/" + tc.getId() + "/try-it-out"),
                null,
                TryItOutResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getGrafanaTraceUrl()).isNull();
    }

    @Test
    @DisplayName("Should not include grafanaTraceUrl in eval summary when Grafana is not configured")
    void shouldNotIncludeGrafanaTraceUrlInEvalSummaryWhenDisabled() {
        UUID runId = metaTestDataHelper.createTestSuiteRun(testSuiteId).getId();
        UUID computationId = UUID.randomUUID();

        insertRunMetricSnapshots(runId, computationId);
        insertEvalSummary(testSuiteId, runId, computationId);

        ResponseEntity<CursorPageResponseDto<Object>> response = restTemplate.exchange(
                apiUrl("/analytics/eval-summaries?filter=runId:eq:" + runId + "&computation=" + computationId
                        + "&size=1"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).hasSize(1);

        @SuppressWarnings("unchecked")
        Map<String, Object> item =
                (Map<String, Object>) response.getBody().getContent().get(0);
        assertThat(item).doesNotContainKey("grafanaTraceUrl");
    }

    private void insertRunMetricSnapshots(UUID runId, UUID computationId) {
        RunMetricSnapshotBatchWriteRequestDto snapshotRequest = RunMetricSnapshotBatchWriteRequestDto.builder()
                .testSuiteRunId(runId)
                .computationId(computationId)
                .computedAtMs(System.currentTimeMillis())
                .snapshots(List.of(RunMetricSnapshotBatchWriteItemDto.builder()
                        .tsmdId(UUID.randomUUID())
                        .tsmdName("Accuracy")
                        .metricDeclarationId(UUID.randomUUID())
                        .metricDeclarationVersionId(UUID.randomUUID())
                        .build()))
                .build();
        restTemplate.postForEntity(
                apiUrl("/analytics/run-metric-snapshots"), jsonEntity(snapshotRequest), BatchWriteResponseDto.class);
    }

    private void insertEvalSummary(UUID suiteId, UUID runId, UUID computationId) {
        ObjectNode testCaseData = JsonNodeFactory.instance.objectNode();
        testCaseData.put("prompt", "test");
        ObjectNode metricValues = JsonNodeFactory.instance.objectNode();
        metricValues.putObject("Accuracy").put("score", 0.95);

        EvalSummaryBatchWriteItemDto item = EvalSummaryBatchWriteItemDto.builder()
                .testCaseRunResultId(UUID.randomUUID())
                .testCaseId(UUID.randomUUID())
                .testCaseName("grafana-disabled-eval-case")
                .runIndex(0)
                .testCaseData(testCaseData)
                .executionStatus(ExecutionStatus.SUCCESS)
                .execDurationMs(1234L)
                .responseStatusCode(200)
                .metricValues(metricValues)
                .build();

        EvalSummaryBatchWriteRequestDto request = EvalSummaryBatchWriteRequestDto.builder()
                .testSuiteId(suiteId)
                .testSuiteRunId(runId)
                .computationId(computationId)
                .computedAtMs(System.currentTimeMillis())
                .items(List.of(item))
                .build();
        restTemplate.postForEntity(
                apiUrl("/analytics/eval-summaries"), jsonEntity(request), EvalSummaryBatchWriteResponseDto.class);
    }
}
