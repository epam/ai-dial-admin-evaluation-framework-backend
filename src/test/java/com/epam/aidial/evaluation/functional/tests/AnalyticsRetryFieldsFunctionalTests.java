package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.service.domain.dto.analytics.BatchWriteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.BatchWriteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.CursorPageResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.ExecutionInfoRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.TestCaseRunResultItemDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.TestCaseRunResultResponseDto;
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

@DisplayName("Analytics Retry Fields Functional Tests")
public abstract class AnalyticsRetryFieldsFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    private UUID testSuiteId;
    private UUID testSuiteRunId;

    @BeforeEach
    void setUp() {
        analyticsTestDataHelper.cleanupResults();
        testSuiteId = metaTestDataHelper.createTestSuite("Retry Fields Suite").getId();
        testSuiteRunId = metaTestDataHelper.createTestSuiteRun(testSuiteId).getId();
    }

    @Test
    @DisplayName("Should batch write results with retry fields and read them back")
    void shouldBatchWriteResultsWithRetryFields() {
        Map<String, Object> logDetails = Map.of(
                "retryAttempts",
                List.of(Map.of("attemptIndex", 1, "statusCode", 500, "errorType", "HTTP_ERROR", "durationMs", 1200)));

        TestCaseRunResultItemDto item =
                buildItemWithRetryFields(UUID.randomUUID(), "case-with-retries", 0, 1, logDetails);

        BatchWriteRequestDto request = BatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(testSuiteRunId)
                .results(List.of(item))
                .build();

        var writeResponse = restTemplate.postForEntity(
                apiUrl("/analytics/test-case-results"), jsonEntity(request), BatchWriteResponseDto.class);
        assertThat(writeResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(writeResponse.getBody()).isNotNull();
        assertThat(writeResponse.getBody().getTotalItems()).isEqualTo(1);

        // Fetch back by ID and verify retry fields
        UUID insertedId = analyticsTestDataHelper.findAnyResultId().orElseThrow();

        var getResponse = restTemplate.getForEntity(
                apiUrl("/analytics/test-case-results/" + insertedId), TestCaseRunResultResponseDto.class);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody()).isNotNull();
        assertThat(getResponse.getBody().getExecutionInfo()).isNotNull();
        assertThat(getResponse.getBody().getExecutionInfo().getRetryCount()).isEqualTo(1);
        assertThat(getResponse.getBody().getExecutionInfo().getLogDetails()).isNotNull();
    }

    @Test
    @DisplayName("Should batch write results with zero retries and null logDetails")
    void shouldBatchWriteResultsWithZeroRetries() {
        TestCaseRunResultItemDto item = buildItemWithRetryFields(UUID.randomUUID(), "case-no-retries", 0, 0, null);

        BatchWriteRequestDto request = BatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(testSuiteRunId)
                .results(List.of(item))
                .build();

        var writeResponse = restTemplate.postForEntity(
                apiUrl("/analytics/test-case-results"), jsonEntity(request), BatchWriteResponseDto.class);
        assertThat(writeResponse.getStatusCode()).isEqualTo(HttpStatus.CREATED);

        UUID insertedId = analyticsTestDataHelper.findAnyResultId().orElseThrow();

        var getResponse = restTemplate.getForEntity(
                apiUrl("/analytics/test-case-results/" + insertedId), TestCaseRunResultResponseDto.class);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody()).isNotNull();
        assertThat(getResponse.getBody().getExecutionInfo().getRetryCount()).isEqualTo(0);
        assertThat(getResponse.getBody().getExecutionInfo().getLogDetails()).isNull();
    }

    @Test
    @DisplayName("Should filter by retryCount")
    void shouldFilterByRetryCount() {
        // Insert result with no retries
        insertResultWithRetryFields("case-no-retry", 0, null);
        // Insert result with 1 retry
        insertResultWithRetryFields("case-1-retry", 1, null);
        // Insert result with 3 retries
        insertResultWithRetryFields("case-3-retries", 3, null);

        // Filter: retryCount > 0 (should match 1-retry and 3-retries)
        var gtResponse = restTemplate.exchange(
                apiUrl("/analytics/test-case-results?filter=suiteId:eq:" + testSuiteId + "&filter=retryCount:gt:0"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<CursorPageResponseDto<Object>>() {});

        assertThat(gtResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(gtResponse.getBody()).isNotNull();
        assertThat(gtResponse.getBody().getContent()).hasSize(2);

        // Filter: retryCount < 2 (should match no-retry and 1-retry)
        var ltResponse = restTemplate.exchange(
                apiUrl("/analytics/test-case-results?filter=suiteId:eq:" + testSuiteId + "&filter=retryCount:lt:2"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<CursorPageResponseDto<Object>>() {});

        assertThat(ltResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(ltResponse.getBody()).isNotNull();
        assertThat(ltResponse.getBody().getContent()).hasSize(2);
    }

    @Test
    @DisplayName("Should store requestBody in results")
    void shouldStoreRequestBodyInResults() {
        ObjectNode testCaseData = JsonNodeFactory.instance.objectNode();
        testCaseData.put("prompt", "test prompt");

        ObjectNode requestBody = JsonNodeFactory.instance.objectNode();
        requestBody.set(
                "messages",
                JsonNodeFactory.instance
                        .arrayNode()
                        .add(JsonNodeFactory.instance
                                .objectNode()
                                .put("role", "user")
                                .put("content", "test prompt")));

        TestCaseRunResultItemDto item = TestCaseRunResultItemDto.builder()
                .testCaseId(UUID.randomUUID())
                .testCaseName("case-with-request-body")
                .runIndex(0)
                .testCaseData(testCaseData)
                .requestBody(requestBody)
                .responseStatusCode(200)
                .executionInfo(ExecutionInfoRequestDto.builder()
                        .status(ExecutionStatus.SUCCESS)
                        .startedAt(1739750400000L)
                        .completedAt(1739750401234L)
                        .traceId("trace-req-body")
                        .retryCount(0)
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

        UUID insertedId = analyticsTestDataHelper.findAnyResultId().orElseThrow();

        var getResponse = restTemplate.getForEntity(
                apiUrl("/analytics/test-case-results/" + insertedId), TestCaseRunResultResponseDto.class);

        assertThat(getResponse.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(getResponse.getBody()).isNotNull();
        assertThat(getResponse.getBody().getRequestBody()).isNotNull();
    }

    // --- helpers ---

    private void insertResultWithRetryFields(String caseName, int retryCount, Object logDetails) {
        TestCaseRunResultItemDto item =
                buildItemWithRetryFields(UUID.randomUUID(), caseName, 0, retryCount, logDetails);

        BatchWriteRequestDto request = BatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(testSuiteRunId)
                .results(List.of(item))
                .build();

        var response = restTemplate.postForEntity(
                apiUrl("/analytics/test-case-results"), jsonEntity(request), BatchWriteResponseDto.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
    }

    private TestCaseRunResultItemDto buildItemWithRetryFields(
            UUID testCaseId, String name, int runIndex, int retryCount, Object logDetails) {
        ObjectNode testCaseData = JsonNodeFactory.instance.objectNode();
        testCaseData.put("prompt", "test prompt for " + name);

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
                        .traceId("trace-" + name)
                        .retryCount(retryCount)
                        .logDetails(logDetails)
                        .build())
                .build();
    }
}
