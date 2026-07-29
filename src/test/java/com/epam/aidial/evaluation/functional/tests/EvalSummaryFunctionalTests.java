package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.data.db.model.TestSuiteRun;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.service.domain.dto.analytics.BatchWriteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.CursorPageResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryBatchWriteItemDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryBatchWriteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryBatchWriteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.EvalSummaryDetailResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.MetricAggregationResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.ResultCountResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunMetricSnapshotBatchWriteItemDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.RunMetricSnapshotBatchWriteRequestDto;
import java.util.ArrayList;
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
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@DisplayName("Eval Summary Functional Tests")
public abstract class EvalSummaryFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    private UUID testSuiteId;
    private UUID testSuiteRunId;

    /**
     * Reads filter on {@code created_at_ms = <the run's createdAt>}, so summaries seeded directly
     * through the DSL (rather than through the batch-write API, which derives it from the run) must
     * carry this value or they are invisible to every read endpoint.
     */
    private long testSuiteRunCreatedAtMs;

    @BeforeEach
    void setUp() {
        analyticsTestDataHelper.cleanupEvalSummaries();
        analyticsTestDataHelper.cleanupRunMetricSnapshots();
        testSuiteId = metaTestDataHelper.createTestSuite("EvalSummary Suite").getId();
        TestSuiteRun run = metaTestDataHelper.createTestSuiteRun(testSuiteId);
        testSuiteRunId = run.getId();
        testSuiteRunCreatedAtMs = run.getCreatedAt();
    }

    // --- Batch Write Tests ---

    @Test
    @DisplayName("Should batch create eval summaries and return 201 with totalItems")
    void shouldBatchCreateEvalSummaries() {
        UUID computationId = UUID.randomUUID();
        insertRunMetricSnapshots(testSuiteRunId, computationId);

        EvalSummaryBatchWriteRequestDto request =
                buildEvalSummaryBatchRequest(testSuiteId, testSuiteRunId, computationId, 3);

        var response = restTemplate.postForEntity(
                apiUrl("/analytics/eval-summaries"), jsonEntity(request), EvalSummaryBatchWriteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTotalItems()).isEqualTo(3);
        assertThat(analyticsTestDataHelper.countEvalSummaries()).isEqualTo(3L);
    }

    @Test
    @DisplayName("Should reject non-existent testSuiteRunId with 404")
    void shouldRejectNonExistentRun() {
        EvalSummaryBatchWriteRequestDto request =
                buildEvalSummaryBatchRequest(testSuiteId, UUID.randomUUID(), UUID.randomUUID(), 1);

        var response =
                restTemplate.postForEntity(apiUrl("/analytics/eval-summaries"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should reject testSuiteId mismatch with 400")
    void shouldRejectSuiteIdMismatch() {
        UUID wrongSuiteId = UUID.randomUUID();
        EvalSummaryBatchWriteRequestDto request =
                buildEvalSummaryBatchRequest(wrongSuiteId, testSuiteRunId, UUID.randomUUID(), 1);

        var response =
                restTemplate.postForEntity(apiUrl("/analytics/eval-summaries"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should reject empty items array with 400")
    void shouldRejectEmptyItems() {
        EvalSummaryBatchWriteRequestDto request = EvalSummaryBatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(testSuiteRunId)
                .computationId(UUID.randomUUID())
                .computedAtMs(System.currentTimeMillis())
                .items(List.of())
                .build();

        var response =
                restTemplate.postForEntity(apiUrl("/analytics/eval-summaries"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should reject non-object metricValues with 400")
    void shouldRejectNonObjectMetricValues() {
        EvalSummaryBatchWriteItemDto item = buildEvalSummaryItem(UUID.randomUUID(), "case-bad", 0);
        item.setMetricValues(JsonNodeFactory.instance.arrayNode().add(1));

        EvalSummaryBatchWriteRequestDto request = EvalSummaryBatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(testSuiteRunId)
                .computationId(UUID.randomUUID())
                .computedAtMs(System.currentTimeMillis())
                .items(List.of(item))
                .build();

        var response =
                restTemplate.postForEntity(apiUrl("/analytics/eval-summaries"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should reject non-numeric leaf in metricValues with 400")
    void shouldRejectNonNumericLeaf() {
        EvalSummaryBatchWriteItemDto item = buildEvalSummaryItem(UUID.randomUUID(), "case-bad", 0);
        ObjectNode metricValues = JsonNodeFactory.instance.objectNode();
        ObjectNode accuracy = metricValues.putObject("Accuracy");
        accuracy.put("score", "not-a-number");
        item.setMetricValues(metricValues);

        EvalSummaryBatchWriteRequestDto request = EvalSummaryBatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(testSuiteRunId)
                .computationId(UUID.randomUUID())
                .computedAtMs(System.currentTimeMillis())
                .items(List.of(item))
                .build();

        var response =
                restTemplate.postForEntity(apiUrl("/analytics/eval-summaries"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should handle idempotent retry without duplicates")
    void shouldHandleIdempotentRetry() {
        UUID computationId = UUID.randomUUID();
        insertRunMetricSnapshots(testSuiteRunId, computationId);

        EvalSummaryBatchWriteRequestDto request =
                buildEvalSummaryBatchRequest(testSuiteId, testSuiteRunId, computationId, 2);

        var response1 = restTemplate.postForEntity(
                apiUrl("/analytics/eval-summaries"), jsonEntity(request), EvalSummaryBatchWriteResponseDto.class);
        assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        var response2 = restTemplate.postForEntity(
                apiUrl("/analytics/eval-summaries"), jsonEntity(request), EvalSummaryBatchWriteResponseDto.class);
        assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        assertThat(analyticsTestDataHelper.countEvalSummaries()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Should reject batch exceeding max items with 400")
    void shouldRejectBatchExceedingMaxItems() {
        List<EvalSummaryBatchWriteItemDto> items = new ArrayList<>();
        for (int ii = 0; ii < 10001; ii++) {
            items.add(buildEvalSummaryItem(UUID.randomUUID(), "case-" + ii, 0));
        }
        EvalSummaryBatchWriteRequestDto request = EvalSummaryBatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(testSuiteRunId)
                .computationId(UUID.randomUUID())
                .computedAtMs(System.currentTimeMillis())
                .items(items)
                .build();

        var response =
                restTemplate.postForEntity(apiUrl("/analytics/eval-summaries"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should reject non-object testCaseData with 400")
    void shouldRejectNonObjectTestCaseData() {
        EvalSummaryBatchWriteItemDto item = buildEvalSummaryItem(UUID.randomUUID(), "case-bad", 0);
        item.setTestCaseData(JsonNodeFactory.instance.arrayNode().add("value"));

        EvalSummaryBatchWriteRequestDto request = EvalSummaryBatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(testSuiteRunId)
                .computationId(UUID.randomUUID())
                .computedAtMs(System.currentTimeMillis())
                .items(List.of(item))
                .build();

        var response =
                restTemplate.postForEntity(apiUrl("/analytics/eval-summaries"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Per-turn summaries of one test case persist as distinct rows keyed by turn_index")
    void shouldPersistPerTurnSummaryRows() {
        UUID computationId = UUID.randomUUID();
        insertRunMetricSnapshots(testSuiteRunId, computationId);

        UUID testCaseId = UUID.randomUUID();
        EvalSummaryBatchWriteItemDto turn0 = buildEvalSummaryItem(testCaseId, "conv", 0);
        turn0.setTurnIndex(0);
        turn0.setTotalTurns(2);
        EvalSummaryBatchWriteItemDto turn1 = buildEvalSummaryItem(testCaseId, "conv", 0);
        turn1.setTurnIndex(1);
        turn1.setTotalTurns(2);

        EvalSummaryBatchWriteRequestDto request = EvalSummaryBatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(testSuiteRunId)
                .computationId(computationId)
                .computedAtMs(System.currentTimeMillis())
                .items(List.of(turn0, turn1))
                .build();

        var response = restTemplate.postForEntity(
                apiUrl("/analytics/eval-summaries"), jsonEntity(request), EvalSummaryBatchWriteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        List<Map<String, Object>> rows = analyticsTestDataHelper.findEvalSummariesByRunId(testSuiteRunId);
        assertThat(rows).hasSize(2);
        assertThat(rows.stream().map(r -> ((Number) r.get("turn_index")).intValue()))
                .containsExactlyInAnyOrder(0, 1);
        assertThat(rows)
                .allSatisfy(r ->
                        assertThat(((Number) r.get("total_turns")).intValue()).isEqualTo(2));
    }

    @Test
    @DisplayName("Single-turn summary write omitting turn fields defaults to turn_index=0, total_turns=1")
    void shouldDefaultTurnFieldsForSingleTurnSummary() {
        UUID computationId = UUID.randomUUID();
        insertRunMetricSnapshots(testSuiteRunId, computationId);

        EvalSummaryBatchWriteRequestDto request =
                buildEvalSummaryBatchRequest(testSuiteId, testSuiteRunId, computationId, 1);

        var response = restTemplate.postForEntity(
                apiUrl("/analytics/eval-summaries"), jsonEntity(request), EvalSummaryBatchWriteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        List<Map<String, Object>> rows = analyticsTestDataHelper.findEvalSummariesByRunId(testSuiteRunId);
        assertThat(rows).hasSize(1);
        assertThat(((Number) rows.get(0).get("turn_index")).intValue()).isEqualTo(0);
        assertThat(((Number) rows.get(0).get("total_turns")).intValue()).isEqualTo(1);
    }

    // --- List Tests ---

    @Test
    @DisplayName("Should list eval summaries with runId filter")
    void shouldListEvalSummariesWithRunIdFilter() {
        UUID computationId = insertEvalSummaries(5);

        var response = restTemplate.exchange(
                apiUrl("/analytics/eval-summaries?filter=runId:eq:" + testSuiteRunId + "&computation=" + computationId
                        + "&size=10"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<CursorPageResponseDto<Object>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getContent()).hasSize(5);
        assertThat(response.getBody().isHasMore()).isFalse();
    }

    @Test
    @DisplayName("Should paginate with cursor")
    void shouldPaginateWithCursor() {
        UUID computationId = insertEvalSummaries(5);

        var page1 = restTemplate.exchange(
                apiUrl("/analytics/eval-summaries?filter=runId:eq:" + testSuiteRunId + "&computation=" + computationId
                        + "&size=2"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<CursorPageResponseDto<Object>>() {});

        assertThat(page1.getBody().getContent()).hasSize(2);
        assertThat(page1.getBody().isHasMore()).isTrue();
        assertThat(page1.getBody().getNextCursor()).isNotNull();

        var page2 = restTemplate.exchange(
                apiUrl("/analytics/eval-summaries?filter=runId:eq:" + testSuiteRunId + "&computation=" + computationId
                        + "&size=2&cursor=" + page1.getBody().getNextCursor()),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<CursorPageResponseDto<Object>>() {});

        assertThat(page2.getBody().getContent()).hasSize(2);
        assertThat(page2.getBody().isHasMore()).isTrue();

        var page3 = restTemplate.exchange(
                apiUrl("/analytics/eval-summaries?filter=runId:eq:" + testSuiteRunId + "&computation=" + computationId
                        + "&size=2&cursor=" + page2.getBody().getNextCursor()),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<CursorPageResponseDto<Object>>() {});

        assertThat(page3.getBody().getContent()).hasSize(1);
        assertThat(page3.getBody().isHasMore()).isFalse();
    }

    @Test
    @DisplayName("Should reject missing runId filter with 400")
    void shouldRejectMissingRunIdFilter() {
        var response = restTemplate.exchange(apiUrl("/analytics/eval-summaries"), HttpMethod.GET, null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should reject sort parameter with 400")
    void shouldRejectSortParameter() {
        var response = restTemplate.exchange(
                apiUrl("/analytics/eval-summaries?filter=runId:eq:" + testSuiteRunId + "&sort=name"),
                HttpMethod.GET,
                null,
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should filter by executionStatus")
    void shouldFilterByExecutionStatus() {
        UUID computationId = UUID.randomUUID();
        insertRunMetricSnapshots(testSuiteRunId, computationId);
        insertEvalSummariesWithStatus(computationId, 3, ExecutionStatus.SUCCESS);
        insertEvalSummariesWithStatus(computationId, 1, ExecutionStatus.FAILED);

        var response = restTemplate.exchange(
                apiUrl("/analytics/eval-summaries?filter=runId:eq:" + testSuiteRunId
                        + "&filter=executionStatus:eq:FAILED&computation=" + computationId),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<CursorPageResponseDto<Object>>() {});

        assertThat(response.getBody().getContent()).hasSize(1);
    }

    @Test
    @DisplayName("Should filter by testCaseName")
    void shouldFilterByTestCaseName() {
        UUID computationId = insertEvalSummaries(3);

        var response = restTemplate.exchange(
                apiUrl("/analytics/eval-summaries?filter=runId:eq:" + testSuiteRunId
                        + "&filter=testCaseName:co:case-1&computation=" + computationId),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<CursorPageResponseDto<Object>>() {});

        assertThat(response.getBody().getContent()).hasSize(1);
    }

    @Test
    @DisplayName("Should filter by JSONB metricValues path")
    void shouldFilterByMetricValuesPath() {
        UUID computationId = UUID.randomUUID();
        insertRunMetricSnapshots(testSuiteRunId, computationId);

        EvalSummaryBatchWriteItemDto item1 = buildEvalSummaryItem(UUID.randomUUID(), "high-score", 0);
        ObjectNode mv1 = JsonNodeFactory.instance.objectNode();
        mv1.putObject("Accuracy").put("score", 0.95);
        item1.setMetricValues(mv1);

        EvalSummaryBatchWriteItemDto item2 = buildEvalSummaryItem(UUID.randomUUID(), "low-score", 0);
        ObjectNode mv2 = JsonNodeFactory.instance.objectNode();
        mv2.putObject("Accuracy").put("score", 0.3);
        item2.setMetricValues(mv2);

        EvalSummaryBatchWriteRequestDto request = EvalSummaryBatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(testSuiteRunId)
                .computationId(computationId)
                .computedAtMs(System.currentTimeMillis())
                .items(List.of(item1, item2))
                .build();
        restTemplate.postForEntity(
                apiUrl("/analytics/eval-summaries"), jsonEntity(request), EvalSummaryBatchWriteResponseDto.class);

        var response = restTemplate.exchange(
                apiUrl("/analytics/eval-summaries?filter=runId:eq:" + testSuiteRunId
                        + "&filter=metricValues.Accuracy.score:ge:0.5&computation=" + computationId),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<CursorPageResponseDto<Object>>() {});

        assertThat(response.getBody().getContent()).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> filteredItem =
                (Map<String, Object>) response.getBody().getContent().get(0);
        assertThat(filteredItem.get("testCaseName")).isEqualTo("high-score");
    }

    @Test
    @DisplayName("Should filter by JSONB testCaseData path")
    void shouldFilterByTestCaseDataPath() {
        UUID computationId = UUID.randomUUID();
        insertRunMetricSnapshots(testSuiteRunId, computationId);

        EvalSummaryBatchWriteItemDto item1 = buildEvalSummaryItem(UUID.randomUUID(), "match-item", 0);
        ObjectNode data1 = JsonNodeFactory.instance.objectNode();
        data1.put("prompt", "greeting-hello");
        item1.setTestCaseData(data1);

        EvalSummaryBatchWriteItemDto item2 = buildEvalSummaryItem(UUID.randomUUID(), "other-item", 0);
        ObjectNode data2 = JsonNodeFactory.instance.objectNode();
        data2.put("prompt", "farewell-bye");
        item2.setTestCaseData(data2);

        EvalSummaryBatchWriteRequestDto request = EvalSummaryBatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(testSuiteRunId)
                .computationId(computationId)
                .computedAtMs(System.currentTimeMillis())
                .items(List.of(item1, item2))
                .build();
        restTemplate.postForEntity(
                apiUrl("/analytics/eval-summaries"), jsonEntity(request), EvalSummaryBatchWriteResponseDto.class);

        var response = restTemplate.exchange(
                apiUrl("/analytics/eval-summaries?filter=runId:eq:" + testSuiteRunId
                        + "&filter=testCaseData.prompt:co:greeting&computation=" + computationId),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<CursorPageResponseDto<Object>>() {});

        assertThat(response.getBody().getContent()).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> filteredItem =
                (Map<String, Object>) response.getBody().getContent().get(0);
        assertThat(filteredItem.get("testCaseName")).isEqualTo("match-item");
    }

    @Test
    @DisplayName("Should resolve computation=latest to the newest computation")
    void shouldResolveComputationLatest() {
        UUID computationId1 = UUID.randomUUID();
        UUID computationId2 = UUID.randomUUID();
        long baseTime = System.currentTimeMillis();

        // Insert older computation with 2 summaries
        insertRunMetricSnapshotsWithTimestamp(testSuiteRunId, computationId1, baseTime);
        insertEvalSummariesWithComputation(computationId1, 2, baseTime);

        // Insert newer computation with 3 summaries
        insertRunMetricSnapshotsWithTimestamp(testSuiteRunId, computationId2, baseTime + 1000);
        insertEvalSummariesWithComputation(computationId2, 3, baseTime + 1000);

        var response = restTemplate.exchange(
                apiUrl("/analytics/eval-summaries?filter=runId:eq:" + testSuiteRunId + "&computation=latest&size=10"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<CursorPageResponseDto<Object>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getContent()).hasSize(3);
    }

    @Test
    @DisplayName("Should use specific computation UUID")
    void shouldUseSpecificComputation() {
        UUID computationId1 = UUID.randomUUID();
        UUID computationId2 = UUID.randomUUID();

        insertRunMetricSnapshots(testSuiteRunId, computationId1);
        insertRunMetricSnapshots(testSuiteRunId, computationId2);

        long baseTime = System.currentTimeMillis();
        insertEvalSummariesWithComputation(computationId1, 2, baseTime);
        insertEvalSummariesWithComputation(computationId2, 3, baseTime + 1000);

        var response = restTemplate.exchange(
                apiUrl("/analytics/eval-summaries?filter=runId:eq:" + testSuiteRunId + "&computation=" + computationId1
                        + "&size=10"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<CursorPageResponseDto<Object>>() {});

        assertThat(response.getBody().getContent()).hasSize(2);
    }

    @Test
    @DisplayName("Should return empty page when no eval summaries exist")
    void shouldReturnEmptyPageWhenNoSnapshots() {
        var response = restTemplate.exchange(
                apiUrl("/analytics/eval-summaries?filter=runId:eq:" + testSuiteRunId + "&computation=latest&size=10"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<CursorPageResponseDto<Object>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getContent()).isEmpty();
        assertThat(response.getBody().isHasMore()).isFalse();
    }

    // --- Get By ID Tests ---

    @Test
    @DisplayName("Should get eval summary by ID including metricInfos")
    void shouldGetEvalSummaryById() {
        UUID computationId = UUID.randomUUID();
        insertRunMetricSnapshots(testSuiteRunId, computationId);

        EvalSummaryBatchWriteItemDto item = buildEvalSummaryItem(UUID.randomUUID(), "detail-case", 0);
        ObjectNode metricInfos = JsonNodeFactory.instance.objectNode();
        metricInfos.putObject("Accuracy").put("version", "1.0");
        item.setMetricInfos(metricInfos);

        EvalSummaryBatchWriteRequestDto request = EvalSummaryBatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(testSuiteRunId)
                .computationId(computationId)
                .computedAtMs(System.currentTimeMillis())
                .items(List.of(item))
                .build();
        restTemplate.postForEntity(
                apiUrl("/analytics/eval-summaries"), jsonEntity(request), EvalSummaryBatchWriteResponseDto.class);

        // List to get the ID
        var listResponse = restTemplate.exchange(
                apiUrl("/analytics/eval-summaries?filter=runId:eq:" + testSuiteRunId + "&computation=" + computationId
                        + "&size=1"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<CursorPageResponseDto<Object>>() {});
        assertThat(listResponse.getBody().getContent()).hasSize(1);

        @SuppressWarnings("unchecked")
        Map<String, Object> firstItem =
                (Map<String, Object>) listResponse.getBody().getContent().get(0);
        String insertedId = (String) firstItem.get("id");

        var response = restTemplate.getForEntity(
                apiUrl("/analytics/eval-summaries/" + insertedId), EvalSummaryDetailResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getId()).hasToString(insertedId);
        assertThat(response.getBody().getTestCaseName()).isEqualTo("detail-case");
        assertThat(response.getBody().getMetricInfos()).isNotNull();
    }

    @Test
    @DisplayName("Detail response includes extractionWarnings, requestBody, responseBody; list response omits them")
    void detailResponseIncludesExtraFieldsAbsentFromList() {
        UUID computationId = UUID.randomUUID();
        insertRunMetricSnapshots(testSuiteRunId, computationId);

        long createdAtMs = System.currentTimeMillis();
        UUID testCaseId = UUID.randomUUID();
        UUID runResultId = analyticsTestDataHelper.createTestRunResult(
                testSuiteRunId,
                testSuiteId,
                testCaseId,
                "detail-extra-fields",
                "{\"prompt\":\"hello\"}",
                "{\"content\":\"world\"}",
                createdAtMs);

        ObjectNode extractionWarnings = JsonNodeFactory.instance.objectNode();
        extractionWarnings.put("field", "missing");

        EvalSummaryBatchWriteItemDto item = EvalSummaryBatchWriteItemDto.builder()
                .testCaseRunResultId(runResultId)
                .testCaseId(testCaseId)
                .testCaseName("detail-extra-fields")
                .runIndex(0)
                .testCaseData(JsonNodeFactory.instance.objectNode())
                .executionStatus(ExecutionStatus.SUCCESS)
                .execDurationMs(100L)
                .metricValues(JsonNodeFactory.instance.objectNode())
                .extractionWarnings(extractionWarnings)
                .build();

        EvalSummaryBatchWriteRequestDto request = EvalSummaryBatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(testSuiteRunId)
                .computationId(computationId)
                .computedAtMs(createdAtMs)
                .items(List.of(item))
                .build();
        restTemplate.postForEntity(
                apiUrl("/analytics/eval-summaries"), jsonEntity(request), EvalSummaryBatchWriteResponseDto.class);

        // Retrieve the inserted ID via list
        var listResponse = restTemplate.exchange(
                apiUrl("/analytics/eval-summaries?filter=runId:eq:" + testSuiteRunId + "&computation=" + computationId
                        + "&size=1"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<CursorPageResponseDto<Object>>() {});
        assertThat(listResponse.getBody().getContent()).hasSize(1);
        @SuppressWarnings("unchecked")
        Map<String, Object> listItem =
                (Map<String, Object>) listResponse.getBody().getContent().get(0);
        String insertedId = (String) listItem.get("id");

        // List response must NOT include requestBody, responseBody, extractionWarnings
        assertThat(listItem).doesNotContainKey("requestBody");
        assertThat(listItem).doesNotContainKey("responseBody");
        assertThat(listItem).doesNotContainKey("extractionWarnings");

        // Detail response must include all three
        var detailResponse = restTemplate.getForEntity(
                apiUrl("/analytics/eval-summaries/" + insertedId), EvalSummaryDetailResponseDto.class);

        assertThat(detailResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(detailResponse.getBody()).isNotNull();
        assertThat(detailResponse.getBody().getExtractionWarnings()).isNotNull();
        assertThat(detailResponse.getBody().getRequestBody()).isNotNull();
        assertThat(detailResponse.getBody().getResponseBody()).isNotNull();
    }

    @Test
    @DisplayName("Should return 404 for non-existent eval summary ID")
    void shouldReturn404ForNonExistentId() {
        var response =
                restTemplate.getForEntity(apiUrl("/analytics/eval-summaries/" + UUID.randomUUID()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    // --- Count Tests ---

    @Test
    @DisplayName("Should count eval summaries matching filters")
    void shouldCountEvalSummaries() {
        UUID computationId = insertEvalSummaries(3);

        var response = restTemplate.getForEntity(
                apiUrl("/analytics/eval-summaries/count?filter=runId:eq:" + testSuiteRunId + "&computation="
                        + computationId),
                ResultCountResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should return 0 count when no eval summaries exist")
    void shouldReturnZeroForNoComputation() {
        var response = restTemplate.getForEntity(
                apiUrl("/analytics/eval-summaries/count?filter=runId:eq:" + testSuiteRunId + "&computation=latest"),
                ResultCountResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should accept transport-failure format: null metric values using real field names")
    void shouldAcceptTransportFailureFormatWithRealFieldNames() {
        UUID computationId = UUID.randomUUID();
        insertRunMetricSnapshots(testSuiteRunId, computationId);

        // Transport failure format: real field names with null values (no synthetic "error" key)
        EvalSummaryBatchWriteItemDto item = buildEvalSummaryItem(UUID.randomUUID(), "transport-failure-case", 0);
        ObjectNode metricValues = JsonNodeFactory.instance.objectNode();
        ObjectNode accuracyValues = metricValues.putObject("Accuracy");
        accuracyValues.putNull("score");
        accuracyValues.putNull("confidence");
        item.setMetricValues(metricValues);
        item.setExecutionStatus(ExecutionStatus.FAILED);

        EvalSummaryBatchWriteRequestDto request = EvalSummaryBatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(testSuiteRunId)
                .computationId(computationId)
                .computedAtMs(System.currentTimeMillis())
                .items(List.of(item))
                .build();

        var response = restTemplate.postForEntity(
                apiUrl("/analytics/eval-summaries"), jsonEntity(request), EvalSummaryBatchWriteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(analyticsTestDataHelper.countEvalSummaries()).isEqualTo(1L);
    }

    // --- Metric-less run tests ---

    @Test
    @DisplayName("Metric-less run: list, count and aggregate all resolve without any run metric snapshots")
    void metricLessRunIsReadableThroughListCountAndAggregate() {
        UUID computationId = UUID.randomUUID();
        insertMetricLessSummaries(computationId, testSuiteRunCreatedAtMs, List.of("ml-1", "ml-2", "ml-3"));

        var listResponse = restTemplate.exchange(
                apiUrl("/analytics/eval-summaries?filter=runId:eq:" + testSuiteRunId + "&size=10"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<CursorPageResponseDto<Object>>() {});

        assertThat(listResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(listResponse.getBody().getContent()).hasSize(3);
        assertThat(listResponse.getBody().getContent()).allSatisfy(item -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) item;
            assertThat(row.get("metricValues")).isEqualTo(Map.of());
        });

        var countResponse = restTemplate.getForEntity(
                apiUrl("/analytics/eval-summaries/count?filter=runId:eq:" + testSuiteRunId),
                ResultCountResponseDto.class);
        assertThat(countResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(countResponse.getBody().getCount()).isEqualTo(3);

        // Aggregation resolves the computation (previously null, since no snapshots existed) and
        // reports an empty metric: nothing to average over.
        var aggregateResponse = restTemplate.getForEntity(
                apiUrl("/analytics/eval-summaries/aggregate?filter=runId:eq:" + testSuiteRunId
                        + "&metrics=Accuracy.score"),
                MetricAggregationResponseDto.class);

        assertThat(aggregateResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(aggregateResponse.getBody().getComputationId()).isEqualTo(computationId);
        assertThat(aggregateResponse.getBody().getMetrics()).hasSize(1);
        var metric = aggregateResponse.getBody().getMetrics().get(0);
        assertThat(metric.getCount()).isEqualTo(0L);
        assertThat(metric.getAvg()).isNull();
        assertThat(metric.getMin()).isNull();
        assertThat(metric.getMax()).isNull();
    }

    @Test
    @DisplayName("Metric-less run with two computations: latest resolves the newer one, explicit UUID the older")
    void metricLessRunResolvesLatestAcrossTwoComputations() {
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        insertMetricLessSummaries(older, testSuiteRunCreatedAtMs, List.of("old-1", "old-2"), 1_000L);
        insertMetricLessSummaries(newer, testSuiteRunCreatedAtMs, List.of("new-1", "new-2", "new-3"), 2_000L);

        var latest = restTemplate.exchange(
                apiUrl("/analytics/eval-summaries?filter=runId:eq:" + testSuiteRunId + "&computation=latest&size=10"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<CursorPageResponseDto<Object>>() {});
        assertThat(latest.getBody().getContent()).hasSize(3);

        var explicitOlder = restTemplate.exchange(
                apiUrl("/analytics/eval-summaries?filter=runId:eq:" + testSuiteRunId + "&computation=" + older
                        + "&size=10"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<CursorPageResponseDto<Object>>() {});
        assertThat(explicitOlder.getBody().getContent()).hasSize(2);
    }

    @Test
    @DisplayName("Latest resolution skips a newer computation that wrote only run metric snapshots")
    void latestResolutionRequiresReadableRows() {
        UUID withSummaries = UUID.randomUUID();
        UUID snapshotsOnly = UUID.randomUUID();
        insertMetricLessSummaries(withSummaries, testSuiteRunCreatedAtMs, List.of("readable-1", "readable-2"), 1_000L);
        // Newer by computed_at_ms, but it produced nothing readable — under the old snapshot-based
        // resolver this would have won and the page would have come back empty.
        insertRunMetricSnapshotsWithTimestamp(testSuiteRunId, snapshotsOnly, 2_000L);

        var response = restTemplate.exchange(
                apiUrl("/analytics/eval-summaries?filter=runId:eq:" + testSuiteRunId + "&computation=latest&size=10"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<CursorPageResponseDto<Object>>() {});

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getContent()).hasSize(2);
        assertThat(response.getBody().getContent()).allSatisfy(item -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> row = (Map<String, Object>) item;
            assertThat((String) row.get("testCaseName")).startsWith("readable-");
        });
    }

    @Test
    @DisplayName("Latest resolution over many rows across two computations returns the newer computation's rows")
    void latestResolutionIsIndependentOfRowCount() {
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        List<String> olderNames = new ArrayList<>();
        List<String> newerNames = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            olderNames.add("bulk-old-" + i);
            newerNames.add("bulk-new-" + i);
        }
        insertMetricLessSummaries(older, testSuiteRunCreatedAtMs, olderNames, 1_000L);
        insertMetricLessSummaries(newer, testSuiteRunCreatedAtMs, newerNames, 2_000L);

        var response = restTemplate.getForEntity(
                apiUrl("/analytics/eval-summaries/count?filter=runId:eq:" + testSuiteRunId + "&computation=latest"),
                ResultCountResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getCount()).isEqualTo(30);
    }

    @Test
    @DisplayName("The latest-computation resolution index exists on test_case_eval_summaries")
    void resolutionIndexExists() {
        var indexDefinition = analyticsTestDataHelper.findIndexDefinition(
                "test_case_eval_summaries", "idx_eval_summaries_run_computed_at");

        assertThat(indexDefinition).isPresent();
        // Contains, not equals: Postgres renders indexdef schema-qualified and with USING btree.
        assertThat(indexDefinition.get()).contains("(test_suite_run_id, computed_at_ms DESC, computation_id)");
    }

    // --- Helpers ---

    private void insertMetricLessSummaries(UUID computationId, long createdAtMs, List<String> testCaseNames) {
        insertMetricLessSummaries(computationId, createdAtMs, testCaseNames, createdAtMs);
    }

    /**
     * Seeds metric-less eval summaries (and their {@code test_case_run_results} rows) directly, with
     * no {@code run_metric_snapshots} — the shape a run over a suite with zero enabled+valid TSMDs
     * produces.
     */
    private void insertMetricLessSummaries(
            UUID computationId, long createdAtMs, List<String> testCaseNames, long computedAtMs) {
        for (String testCaseName : testCaseNames) {
            analyticsTestDataHelper.createTestRunResult(
                    testSuiteRunId, testSuiteId, UUID.randomUUID(), testCaseName, "{}", "{}", createdAtMs);
            analyticsTestDataHelper.createEvalSummary(
                    testSuiteId,
                    testSuiteRunId,
                    computationId,
                    testCaseName,
                    ExecutionStatus.SUCCESS.name(),
                    100L,
                    createdAtMs,
                    computedAtMs,
                    "{\"prompt\":\"p\"}",
                    "{}");
        }
    }

    private EvalSummaryBatchWriteRequestDto buildEvalSummaryBatchRequest(
            UUID suiteId, UUID runId, UUID computationId, int count) {
        List<EvalSummaryBatchWriteItemDto> items = new ArrayList<>();
        for (int ii = 0; ii < count; ii++) {
            items.add(buildEvalSummaryItem(UUID.randomUUID(), "case-" + ii, 0));
        }
        return EvalSummaryBatchWriteRequestDto.builder()
                .testSuiteId(suiteId)
                .testSuiteRunId(runId)
                .computationId(computationId)
                .computedAtMs(System.currentTimeMillis())
                .items(items)
                .build();
    }

    private EvalSummaryBatchWriteItemDto buildEvalSummaryItem(UUID testCaseId, String name, int runIndex) {
        ObjectNode testCaseData = JsonNodeFactory.instance.objectNode();
        testCaseData.put("prompt", "test prompt");

        ObjectNode metricValues = JsonNodeFactory.instance.objectNode();
        ObjectNode accuracy = metricValues.putObject("Accuracy");
        accuracy.put("score", 0.95);
        accuracy.put("confidence", 0.87);

        return EvalSummaryBatchWriteItemDto.builder()
                .testCaseRunResultId(UUID.randomUUID())
                .testCaseId(testCaseId)
                .testCaseName(name)
                .runIndex(runIndex)
                .testCaseData(testCaseData)
                .executionStatus(ExecutionStatus.SUCCESS)
                .execDurationMs(1234L)
                .responseStatusCode(200)
                .metricValues(metricValues)
                .build();
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

    private void insertRunMetricSnapshotsWithTimestamp(UUID runId, UUID computationId, long computedAtMs) {
        RunMetricSnapshotBatchWriteRequestDto snapshotRequest = RunMetricSnapshotBatchWriteRequestDto.builder()
                .testSuiteRunId(runId)
                .computationId(computationId)
                .computedAtMs(computedAtMs)
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

    /**
     * Inserts eval summaries with a fresh computation, including prerequisite snapshots.
     *
     * @return the computation ID used
     */
    private UUID insertEvalSummaries(int count) {
        UUID computationId = UUID.randomUUID();
        insertRunMetricSnapshots(testSuiteRunId, computationId);

        EvalSummaryBatchWriteRequestDto request =
                buildEvalSummaryBatchRequest(testSuiteId, testSuiteRunId, computationId, count);
        restTemplate.postForEntity(
                apiUrl("/analytics/eval-summaries"), jsonEntity(request), EvalSummaryBatchWriteResponseDto.class);
        return computationId;
    }

    private void insertEvalSummariesWithStatus(UUID computationId, int count, ExecutionStatus status) {
        List<EvalSummaryBatchWriteItemDto> items = new ArrayList<>();
        for (int ii = 0; ii < count; ii++) {
            EvalSummaryBatchWriteItemDto item =
                    buildEvalSummaryItem(UUID.randomUUID(), "case-status-" + status + "-" + ii, 0);
            item.setExecutionStatus(status);
            items.add(item);
        }
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

    /**
     * {@code computedAtMs} is explicit rather than "now": latest-computation resolution orders by
     * {@code computed_at_ms}, so two computations written in the same millisecond would make
     * {@code ORDER BY computed_at_ms DESC LIMIT 1} arbitrary.
     */
    private void insertEvalSummariesWithComputation(UUID computationId, int count, long computedAtMs) {
        List<EvalSummaryBatchWriteItemDto> items = new ArrayList<>();
        for (int ii = 0; ii < count; ii++) {
            items.add(buildEvalSummaryItem(UUID.randomUUID(), "comp-case-" + computationId + "-" + ii, 0));
        }
        EvalSummaryBatchWriteRequestDto request = EvalSummaryBatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(testSuiteRunId)
                .computationId(computationId)
                .computedAtMs(computedAtMs)
                .items(items)
                .build();
        restTemplate.postForEntity(
                apiUrl("/analytics/eval-summaries"), jsonEntity(request), EvalSummaryBatchWriteResponseDto.class);
    }
}
