package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.service.domain.dto.analytics.BatchWriteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryBatchWriteItemDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryBatchWriteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryBatchWriteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.MetricAggregationResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunMetricSnapshotBatchWriteItemDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunMetricSnapshotBatchWriteRequestDto;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@DisplayName("Eval Summary Aggregation Functional Tests")
public abstract class EvalSummaryAggregationFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    private UUID testSuiteId;
    private UUID testSuiteRunId;

    @BeforeEach
    void setUp() {
        analyticsTestDataHelper.cleanupEvalSummaries();
        analyticsTestDataHelper.cleanupRunMetricSnapshots();
        testSuiteId = metaTestDataHelper.createTestSuite("Aggregation Suite").getId();
        testSuiteRunId = metaTestDataHelper.createTestSuiteRun(testSuiteId).getId();
    }

    @Test
    @DisplayName("Should aggregate metric values per run")
    void shouldAggregateMetricValues() {
        UUID computationId = UUID.randomUUID();
        insertRunMetricSnapshots(testSuiteRunId, computationId);
        insertEvalSummariesWithScores(computationId, List.of(0.8, 0.9, 1.0));

        var response = restTemplate.getForEntity(
                apiUrl("/analytics/eval-summaries/aggregate?filter=runId:eq:" + testSuiteRunId
                        + "&computation=" + computationId
                        + "&metrics=Accuracy.score"),
                MetricAggregationResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getComputationId()).isEqualTo(computationId);
        assertThat(response.getBody().getMetrics()).hasSize(1);

        var metric = response.getBody().getMetrics().get(0);
        assertThat(metric.getMetric()).isEqualTo("Accuracy");
        assertThat(metric.getOutput()).isEqualTo("score");
        assertThat(metric.getAvg()).isCloseTo(0.9, org.assertj.core.api.Assertions.within(0.001));
        assertThat(metric.getMin()).isCloseTo(0.8, org.assertj.core.api.Assertions.within(0.001));
        assertThat(metric.getMax()).isCloseTo(1.0, org.assertj.core.api.Assertions.within(0.001));
        assertThat(metric.getCount()).isEqualTo(3L);
    }

    @Test
    @DisplayName("Should aggregate multiple metrics in one request")
    void shouldAggregateMultipleMetrics() {
        UUID computationId = UUID.randomUUID();
        insertRunMetricSnapshots(testSuiteRunId, computationId);
        insertEvalSummariesWithMultipleMetrics(computationId);

        var response = restTemplate.getForEntity(
                apiUrl("/analytics/eval-summaries/aggregate?filter=runId:eq:" + testSuiteRunId
                        + "&computation=" + computationId
                        + "&metrics=Accuracy.score&metrics=Recall.value"),
                MetricAggregationResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getMetrics()).hasSize(2);

        var accuracyMetric = response.getBody().getMetrics().stream()
                .filter(mm -> "Accuracy".equals(mm.getMetric()))
                .findFirst()
                .orElseThrow();
        assertThat(accuracyMetric.getOutput()).isEqualTo("score");
        assertThat(accuracyMetric.getCount()).isEqualTo(2L);

        var recallMetric = response.getBody().getMetrics().stream()
                .filter(mm -> "Recall".equals(mm.getMetric()))
                .findFirst()
                .orElseThrow();
        assertThat(recallMetric.getOutput()).isEqualTo("value");
        assertThat(recallMetric.getCount()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Should return empty metrics when computation not found")
    void shouldReturnEmptyMetricsWhenComputationNotFound() {
        var response = restTemplate.getForEntity(
                apiUrl("/analytics/eval-summaries/aggregate?filter=runId:eq:" + testSuiteRunId
                        + "&computation=latest&metrics=Accuracy.score"),
                MetricAggregationResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getComputationId()).isNull();
        assertThat(response.getBody().getMetrics()).isEmpty();
    }

    @Test
    @DisplayName("Should reject missing metrics param with 400")
    void shouldRejectMissingMetricsParam() {
        var response = restTemplate.getForEntity(
                apiUrl("/analytics/eval-summaries/aggregate?filter=runId:eq:" + testSuiteRunId), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should reject more than 50 metrics with 400")
    void shouldRejectTooManyMetrics() {
        String metricsParams = IntStream.range(0, 51)
                .mapToObj(ii -> "metrics=Metric" + ii + ".output")
                .collect(Collectors.joining("&"));

        var response = restTemplate.getForEntity(
                apiUrl("/analytics/eval-summaries/aggregate?filter=runId:eq:" + testSuiteRunId + "&" + metricsParams),
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should reject missing runId filter with 400")
    void shouldRejectMissingRunIdFilter() {
        var response = restTemplate.getForEntity(
                apiUrl("/analytics/eval-summaries/aggregate?metrics=Accuracy.score"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- Helpers ---

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

    private void insertEvalSummariesWithScores(UUID computationId, List<Double> scores) {
        List<EvalSummaryBatchWriteItemDto> items = new ArrayList<>();
        for (int ii = 0; ii < scores.size(); ii++) {
            EvalSummaryBatchWriteItemDto item = buildBaseItem("score-case-" + ii);
            ObjectNode metricValues = JsonNodeFactory.instance.objectNode();
            metricValues.putObject("Accuracy").put("score", scores.get(ii));
            item.setMetricValues(metricValues);
            items.add(item);
        }
        postEvalSummaries(computationId, items);
    }

    private void insertEvalSummariesWithMultipleMetrics(UUID computationId) {
        List<EvalSummaryBatchWriteItemDto> items = new ArrayList<>();
        for (int ii = 0; ii < 2; ii++) {
            EvalSummaryBatchWriteItemDto item = buildBaseItem("multi-metric-" + ii);
            ObjectNode metricValues = JsonNodeFactory.instance.objectNode();
            metricValues.putObject("Accuracy").put("score", 0.8 + ii * 0.1);
            metricValues.putObject("Recall").put("value", 0.7 + ii * 0.1);
            item.setMetricValues(metricValues);
            items.add(item);
        }
        postEvalSummaries(computationId, items);
    }

    private EvalSummaryBatchWriteItemDto buildBaseItem(String name) {
        ObjectNode testCaseData = JsonNodeFactory.instance.objectNode();
        testCaseData.put("prompt", "test");

        ObjectNode defaultMetrics = JsonNodeFactory.instance.objectNode();
        defaultMetrics.putObject("Default").put("value", 1.0);

        return EvalSummaryBatchWriteItemDto.builder()
                .testCaseRunResultId(UUID.randomUUID())
                .testCaseId(UUID.randomUUID())
                .testCaseName(name)
                .runIndex(0)
                .testCaseData(testCaseData)
                .executionStatus(ExecutionStatus.SUCCESS)
                .execDurationMs(500L)
                .responseStatusCode(200)
                .metricValues(defaultMetrics)
                .build();
    }

    private void postEvalSummaries(UUID computationId, List<EvalSummaryBatchWriteItemDto> items) {
        EvalSummaryBatchWriteRequestDto request = EvalSummaryBatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(testSuiteRunId)
                .computationId(computationId)
                .computedAtMs(System.currentTimeMillis())
                .items(items)
                .build();
        restTemplate.postForEntity(
                apiUrl("/analytics/eval-summaries"), jsonEntity(request), EvalSummaryBatchWriteResponseDto.class);
    }
}
