package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.service.domain.dto.analytics.BatchWriteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunMetricSnapshotBatchWriteItemDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunMetricSnapshotBatchWriteRequestDto;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

@DisplayName("Run Metric Snapshot Functional Tests")
public abstract class RunMetricSnapshotFunctionalTests extends BaseFunctionalTest {

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
        testSuiteId = metaTestDataHelper.createTestSuite("Snapshot Suite").getId();
        testSuiteRunId = metaTestDataHelper.createTestSuiteRun(testSuiteId).getId();
    }

    @Test
    @DisplayName("Should batch create snapshots and return 201 with totalItems")
    void shouldBatchCreateSnapshots() {
        RunMetricSnapshotBatchWriteRequestDto request = buildSnapshotRequest(testSuiteRunId, UUID.randomUUID(), 3);

        var response = restTemplate.postForEntity(
                apiUrl("/analytics/run-metric-snapshots"), jsonEntity(request), BatchWriteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTotalItems()).isEqualTo(3);
        assertThat(analyticsTestDataHelper.countRunMetricSnapshots()).isEqualTo(3L);
    }

    @Test
    @DisplayName("Should reject non-existent testSuiteRunId with 404")
    void shouldRejectNonExistentRun() {
        RunMetricSnapshotBatchWriteRequestDto request = buildSnapshotRequest(UUID.randomUUID(), UUID.randomUUID(), 1);

        var response = restTemplate.postForEntity(
                apiUrl("/analytics/run-metric-snapshots"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should reject empty snapshots array with 400")
    void shouldRejectEmptySnapshots() {
        RunMetricSnapshotBatchWriteRequestDto request = RunMetricSnapshotBatchWriteRequestDto.builder()
                .testSuiteRunId(testSuiteRunId)
                .computationId(UUID.randomUUID())
                .computedAtMs(System.currentTimeMillis())
                .snapshots(List.of())
                .build();

        var response = restTemplate.postForEntity(
                apiUrl("/analytics/run-metric-snapshots"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should handle idempotent retry without duplicates")
    void shouldHandleIdempotentRetry() {
        RunMetricSnapshotBatchWriteRequestDto request = buildSnapshotRequest(testSuiteRunId, UUID.randomUUID(), 2);

        var response1 = restTemplate.postForEntity(
                apiUrl("/analytics/run-metric-snapshots"), jsonEntity(request), BatchWriteResponseDto.class);
        assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var response2 = restTemplate.postForEntity(
                apiUrl("/analytics/run-metric-snapshots"), jsonEntity(request), BatchWriteResponseDto.class);
        assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(analyticsTestDataHelper.countRunMetricSnapshots()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Should list snapshots by runId filter")
    void shouldListSnapshotsByRunId() {
        UUID computationId = UUID.randomUUID();
        RunMetricSnapshotBatchWriteRequestDto request = buildSnapshotRequest(testSuiteRunId, computationId, 3);
        restTemplate.postForEntity(
                apiUrl("/analytics/run-metric-snapshots"), jsonEntity(request), BatchWriteResponseDto.class);

        var response = restTemplate.exchange(
                apiUrl("/analytics/run-metric-snapshots?filter=runId:eq:" + testSuiteRunId),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<Object>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(3);
    }

    @Test
    @DisplayName("Should reject missing runId filter with 400")
    void shouldRejectMissingRunIdFilter() {
        var response =
                restTemplate.exchange(apiUrl("/analytics/run-metric-snapshots"), HttpMethod.GET, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should resolve latest computation ID correctly")
    void shouldResolveLatestComputationId() {
        UUID computationId1 = UUID.randomUUID();
        UUID computationId2 = UUID.randomUUID();
        long baseTime = System.currentTimeMillis();

        // Insert first computation
        RunMetricSnapshotBatchWriteRequestDto request1 = RunMetricSnapshotBatchWriteRequestDto.builder()
                .testSuiteRunId(testSuiteRunId)
                .computationId(computationId1)
                .computedAtMs(baseTime)
                .snapshots(List.of(buildSnapshotItem("MetricA")))
                .build();
        restTemplate.postForEntity(
                apiUrl("/analytics/run-metric-snapshots"), jsonEntity(request1), BatchWriteResponseDto.class);

        // Insert second computation (later)
        RunMetricSnapshotBatchWriteRequestDto request2 = RunMetricSnapshotBatchWriteRequestDto.builder()
                .testSuiteRunId(testSuiteRunId)
                .computationId(computationId2)
                .computedAtMs(baseTime + 1000)
                .snapshots(List.of(buildSnapshotItem("MetricB")))
                .build();
        restTemplate.postForEntity(
                apiUrl("/analytics/run-metric-snapshots"), jsonEntity(request2), BatchWriteResponseDto.class);

        // List all snapshots for the run — should be ordered by computed_at_ms DESC
        var response = restTemplate.exchange(
                apiUrl("/analytics/run-metric-snapshots?filter=runId:eq:" + testSuiteRunId),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<List<Object>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).hasSize(2);

        @SuppressWarnings("unchecked")
        Map<String, Object> newerSnapshot =
                (Map<String, Object>) response.getBody().get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> olderSnapshot =
                (Map<String, Object>) response.getBody().get(1);
        assertThat(newerSnapshot.get("computationId")).isEqualTo(computationId2.toString());
        assertThat(newerSnapshot.get("tsmdName")).isEqualTo("MetricB");
        assertThat(olderSnapshot.get("computationId")).isEqualTo(computationId1.toString());
        assertThat(olderSnapshot.get("tsmdName")).isEqualTo("MetricA");
    }

    // --- Helpers ---

    private RunMetricSnapshotBatchWriteRequestDto buildSnapshotRequest(UUID runId, UUID computationId, int count) {
        List<RunMetricSnapshotBatchWriteItemDto> items = new java.util.ArrayList<>();
        for (int ii = 0; ii < count; ii++) {
            items.add(buildSnapshotItem("Metric-" + ii));
        }
        return RunMetricSnapshotBatchWriteRequestDto.builder()
                .testSuiteRunId(runId)
                .computationId(computationId)
                .computedAtMs(System.currentTimeMillis())
                .snapshots(items)
                .build();
    }

    private RunMetricSnapshotBatchWriteItemDto buildSnapshotItem(String name) {
        return RunMetricSnapshotBatchWriteItemDto.builder()
                .tsmdId(UUID.randomUUID())
                .tsmdName(name)
                .metricDeclarationId(UUID.randomUUID())
                .metricDeclarationVersionId(UUID.randomUUID())
                .build();
    }
}
