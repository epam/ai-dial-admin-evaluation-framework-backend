package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.service.domain.dto.analytics.BatchWriteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.BatchWriteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.ExecutionInfoRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.TestCaseRunResultItemDto;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@DisplayName("Analytics Result Batch Write Tests")
public abstract class AnalyticsResultBatchWriteFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private ObjectMapper objectMapper;

    private UUID testSuiteId;
    private UUID testSuiteRunId;

    @BeforeEach
    void setUp() {
        analyticsTestDataHelper.cleanupResults();
        testSuiteId = metaTestDataHelper.createTestSuite("Batch Write Suite").getId();
        testSuiteRunId = metaTestDataHelper.createTestSuiteRun(testSuiteId).getId();
    }

    @Test
    @DisplayName("Should batch create results and return 201 with totalItems")
    void shouldBatchCreateResults() {
        BatchWriteRequestDto request = buildBatchRequest(testSuiteId, testSuiteRunId, 3);

        var response = restTemplate.postForEntity(
                apiUrl("/analytics/test-case-results"), jsonEntity(request), BatchWriteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getTotalItems()).isEqualTo(3);

        // Verify created_at_ms matches the run's created_at_ms
        Long runCreatedAt =
                metaTestDataHelper.findRun(testSuiteRunId).orElseThrow().getCreatedAt();
        Long resultCreatedAt = analyticsTestDataHelper.findAnyResultCreatedAt().orElseThrow();
        assertThat(resultCreatedAt).isEqualTo(runCreatedAt);
    }

    @Test
    @DisplayName("Should reject non-existent testSuiteRunId with 404")
    void shouldRejectNonExistentRun() {
        BatchWriteRequestDto request = buildBatchRequest(testSuiteId, UUID.randomUUID(), 1);

        var response =
                restTemplate.postForEntity(apiUrl("/analytics/test-case-results"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("Should reject testSuiteId mismatch with 400")
    void shouldRejectSuiteIdMismatch() {
        UUID wrongSuiteId = UUID.randomUUID();
        BatchWriteRequestDto request = buildBatchRequest(wrongSuiteId, testSuiteRunId, 1);

        var response =
                restTemplate.postForEntity(apiUrl("/analytics/test-case-results"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should reject empty results array with 400")
    void shouldRejectEmptyResults() {
        BatchWriteRequestDto request = BatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(testSuiteRunId)
                .results(List.of())
                .build();

        var response =
                restTemplate.postForEntity(apiUrl("/analytics/test-case-results"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should reject completedAt < startedAt with 400")
    void shouldRejectInvalidTimestamps() {
        TestCaseRunResultItemDto item = buildItem(UUID.randomUUID(), "case-1", 0);
        item.getExecutionInfo().setCompletedAt(item.getExecutionInfo().getStartedAt() - 1000);

        BatchWriteRequestDto request = BatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(testSuiteRunId)
                .results(List.of(item))
                .build();

        var response =
                restTemplate.postForEntity(apiUrl("/analytics/test-case-results"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should reject testCaseData as array with 400")
    void shouldRejectTestCaseDataAsArray() {
        TestCaseRunResultItemDto item = buildItem(UUID.randomUUID(), "case-1", 0);
        item.setTestCaseData(objectMapper.createArrayNode().add("value"));

        BatchWriteRequestDto request = BatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(testSuiteRunId)
                .results(List.of(item))
                .build();

        var response =
                restTemplate.postForEntity(apiUrl("/analytics/test-case-results"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should reject runIndex > 99999 with 400")
    void shouldRejectInvalidRunIndex() {
        TestCaseRunResultItemDto item = buildItem(UUID.randomUUID(), "case-1", 100000);

        BatchWriteRequestDto request = BatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(testSuiteRunId)
                .results(List.of(item))
                .build();

        var response =
                restTemplate.postForEntity(apiUrl("/analytics/test-case-results"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should reject batch exceeding max items with 400")
    void shouldRejectBatchExceedingMaxItems() {
        List<TestCaseRunResultItemDto> items = new ArrayList<>();
        for (int i = 0; i < 10001; i++) {
            items.add(buildItem(UUID.randomUUID(), "case-" + i, 0));
        }
        BatchWriteRequestDto request = BatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(testSuiteRunId)
                .results(items)
                .build();

        var response =
                restTemplate.postForEntity(apiUrl("/analytics/test-case-results"), jsonEntity(request), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should write results with optional requestBody and responseBody")
    void shouldWriteResultsWithOptionalFields() {
        ObjectNode requestBodyNode = JsonNodeFactory.instance.objectNode();
        requestBodyNode.put("model", "gpt-4");
        requestBodyNode.put("temperature", 0.7);

        ObjectNode responseBodyNode = JsonNodeFactory.instance.objectNode();
        responseBodyNode.put("content", "Hello world");
        responseBodyNode.put("tokenCount", 42);

        ObjectNode testCaseData = JsonNodeFactory.instance.objectNode();
        testCaseData.put("prompt", "test prompt");

        TestCaseRunResultItemDto item = TestCaseRunResultItemDto.builder()
                .testCaseId(UUID.randomUUID())
                .testCaseName("case-with-bodies")
                .runIndex(0)
                .testCaseData(testCaseData)
                .requestBody(requestBodyNode)
                .responseBody(responseBodyNode)
                .responseStatusCode(200)
                .executionInfo(ExecutionInfoRequestDto.builder()
                        .status(ExecutionStatus.SUCCESS)
                        .startedAt(1739750400000L)
                        .completedAt(1739750401234L)
                        .traceId("trace-bodies")
                        .build())
                .build();

        BatchWriteRequestDto request = BatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(testSuiteRunId)
                .results(List.of(item))
                .build();

        var writeResponse = restTemplate.postForEntity(
                apiUrl("/analytics/test-case-results"), jsonEntity(request), BatchWriteResponseDto.class);
        assertThat(writeResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Fetch back and verify optional fields
        String insertedId =
                analyticsTestDataHelper.findAnyResultId().orElseThrow().toString();

        var getResponse = restTemplate.getForEntity(apiUrl("/analytics/test-case-results/" + insertedId), String.class);
        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody()).contains("\"requestBody\"");
        assertThat(getResponse.getBody()).contains("\"responseBody\"");
        assertThat(getResponse.getBody()).contains("\"model\"");
        assertThat(getResponse.getBody()).contains("\"content\"");
    }

    @Test
    @DisplayName("Should handle idempotent retry - no duplicate data")
    void shouldHandleIdempotentRetry() {
        BatchWriteRequestDto request = buildBatchRequest(testSuiteId, testSuiteRunId, 2);

        // First write
        var response1 = restTemplate.postForEntity(
                apiUrl("/analytics/test-case-results"), jsonEntity(request), BatchWriteResponseDto.class);
        assertThat(response1.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Second write (same data)
        var response2 = restTemplate.postForEntity(
                apiUrl("/analytics/test-case-results"), jsonEntity(request), BatchWriteResponseDto.class);
        assertThat(response2.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        // Verify no duplicates
        assertThat(analyticsTestDataHelper.countAll()).isEqualTo(2L);
    }

    @Test
    @DisplayName("Should skip intra-batch duplicates")
    void shouldSkipIntraBatchDuplicates() {
        UUID testCaseId = UUID.randomUUID();
        TestCaseRunResultItemDto item1 = buildItem(testCaseId, "case-1", 0);
        TestCaseRunResultItemDto item2 = buildItem(testCaseId, "case-1", 0); // same testCaseId + runIndex

        BatchWriteRequestDto request = BatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(testSuiteRunId)
                .results(List.of(item1, item2))
                .build();

        var response = restTemplate.postForEntity(
                apiUrl("/analytics/test-case-results"), jsonEntity(request), BatchWriteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().getTotalItems()).isEqualTo(2);

        assertThat(analyticsTestDataHelper.countAll()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Should import a multi-turn multiTurn as distinct per-turn rows")
    void shouldImportMultiTurnAsDistinctRows() {
        UUID multiTurnCaseId = UUID.randomUUID();
        List<TestCaseRunResultItemDto> turns = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            TestCaseRunResultItemDto turn = buildItem(multiTurnCaseId, "multi-turn-case", 0);
            turn.setTurnIndex(i);
            turn.setTotalTurns(3);
            turns.add(turn);
        }
        BatchWriteRequestDto request = BatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(testSuiteRunId)
                .results(turns)
                .build();

        var response = restTemplate.postForEntity(
                apiUrl("/analytics/test-case-results"), jsonEntity(request), BatchWriteResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody().getTotalItems()).isEqualTo(3);

        assertThat(analyticsTestDataHelper.countAll()).isEqualTo(3L);

        List<Map<String, Object>> rows = analyticsTestDataHelper.findResultsByRunId(testSuiteRunId);
        assertThat(rows).extracting(row -> row.get("turn_index")).containsExactlyInAnyOrder(0, 1, 2);
        assertThat(rows).allSatisfy(row -> assertThat(row.get("total_turns")).isEqualTo(3));
    }

    @Test
    @DisplayName("Should default turn fields to 0/1 when omitted by single-turn callers")
    void shouldDefaultTurnFieldsWhenOmitted() {
        TestCaseRunResultItemDto item = buildItem(UUID.randomUUID(), "single-turn-case", 0);

        BatchWriteRequestDto request = BatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(testSuiteRunId)
                .results(List.of(item))
                .build();

        var response = restTemplate.postForEntity(
                apiUrl("/analytics/test-case-results"), jsonEntity(request), BatchWriteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        List<Map<String, Object>> rows = analyticsTestDataHelper.findResultsByRunId(testSuiteRunId);
        assertThat(rows).hasSize(1);
        assertThat(rows.getFirst().get("turn_index")).isEqualTo(0);
        assertThat(rows.getFirst().get("total_turns")).isEqualTo(1);
    }

    // --- helpers ---

    private BatchWriteRequestDto buildBatchRequest(UUID suiteId, UUID runId, int count) {
        List<TestCaseRunResultItemDto> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            items.add(buildItem(UUID.randomUUID(), "test-case-" + i, 0));
        }
        return BatchWriteRequestDto.builder()
                .testSuiteId(suiteId)
                .testSuiteRunId(runId)
                .results(items)
                .build();
    }

    private TestCaseRunResultItemDto buildItem(UUID testCaseId, String name, int runIndex) {
        ObjectNode testCaseData = JsonNodeFactory.instance.objectNode();
        testCaseData.put("prompt", "test prompt");

        return TestCaseRunResultItemDto.builder()
                .testCaseId(testCaseId)
                .testCaseName(name)
                .runIndex(runIndex)
                .testCaseData(testCaseData)
                .responseStatusCode(200)
                .executionInfo(ExecutionInfoRequestDto.builder()
                        .status(ExecutionStatus.SUCCESS)
                        .startedAt(1739750400000L)
                        .completedAt(1739750401234L)
                        .traceId("trace-123")
                        .build())
                .build();
    }
}
