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
import java.util.ArrayList;
import java.util.List;
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

@DisplayName("Analytics Result List Tests")
public abstract class AnalyticsResultListFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    private UUID testSuiteId;
    private UUID testSuiteRunId;

    @BeforeEach
    void setUp() {
        analyticsTestDataHelper.cleanupResults();
        testSuiteId = metaTestDataHelper.createTestSuite("List Suite").getId();
        testSuiteRunId = metaTestDataHelper.createTestSuiteRun(testSuiteId).getId();
    }

    @Test
    @DisplayName("Should list results with suiteId filter")
    void shouldListResultsWithSuiteIdFilter() {
        insertResults(5);

        var response = restTemplate.exchange(
                apiUrl("/analytics/test-case-results?filter=suiteId:eq:" + testSuiteId + "&size=10"),
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
        insertResults(5);

        // First page
        var page1 = restTemplate.exchange(
                apiUrl("/analytics/test-case-results?filter=suiteId:eq:" + testSuiteId + "&size=2"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<CursorPageResponseDto<Object>>() {});

        assertThat(page1.getBody().getContent()).hasSize(2);
        assertThat(page1.getBody().isHasMore()).isTrue();
        assertThat(page1.getBody().getNextCursor()).isNotNull();

        // Second page
        var page2 = restTemplate.exchange(
                apiUrl("/analytics/test-case-results?filter=suiteId:eq:" + testSuiteId + "&size=2&cursor="
                        + page1.getBody().getNextCursor()),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<CursorPageResponseDto<Object>>() {});

        assertThat(page2.getBody().getContent()).hasSize(2);
        assertThat(page2.getBody().isHasMore()).isTrue();

        // Third page (last)
        var page3 = restTemplate.exchange(
                apiUrl("/analytics/test-case-results?filter=suiteId:eq:" + testSuiteId + "&size=2&cursor="
                        + page2.getBody().getNextCursor()),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<CursorPageResponseDto<Object>>() {});

        assertThat(page3.getBody().getContent()).hasSize(1);
        assertThat(page3.getBody().isHasMore()).isFalse();
    }

    @Test
    @DisplayName("Should reject missing suiteId filter with 400")
    void shouldRejectMissingSuiteIdFilter() {
        var response = restTemplate.exchange(
                apiUrl("/analytics/test-case-results?filter=runId:eq:" + testSuiteRunId),
                HttpMethod.GET,
                null,
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should reject sort parameter with 400")
    void shouldRejectSortParameter() {
        var response = restTemplate.exchange(
                apiUrl("/analytics/test-case-results?filter=suiteId:eq:" + testSuiteId + "&sort=name"),
                HttpMethod.GET,
                null,
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should filter by executionStatus")
    void shouldFilterByExecutionStatus() {
        insertResults(3); // all SUCCESS
        insertResultWithStatus(ExecutionStatus.FAILED);

        var response = restTemplate.exchange(
                apiUrl("/analytics/test-case-results?filter=suiteId:eq:" + testSuiteId
                        + "&filter=executionStatus:eq:FAILED"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<CursorPageResponseDto<Object>>() {});

        assertThat(response.getBody().getContent()).hasSize(1);
    }

    @Test
    @DisplayName("Should filter by JSONB testCaseData path")
    void shouldFilterByJsonbPath() {
        insertResultWithTestCaseData("greeting-prompt");
        insertResultWithTestCaseData("other-prompt");

        var response = restTemplate.exchange(
                apiUrl("/analytics/test-case-results?filter=suiteId:eq:" + testSuiteId
                        + "&filter=testCaseData.prompt:co:greeting"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<CursorPageResponseDto<Object>>() {});

        assertThat(response.getBody().getContent()).hasSize(1);
    }

    @Test
    @DisplayName("Should filter by runId")
    void shouldFilterByRunId() {
        insertResults(3);

        // Create a second run and insert results under it
        UUID anotherRunId = metaTestDataHelper.createTestSuiteRun(testSuiteId).getId();
        TestCaseRunResultItemDto item =
                buildItem(UUID.randomUUID(), "case-other-run", 0, ExecutionStatus.SUCCESS, "prompt-other");
        BatchWriteRequestDto request = BatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(anotherRunId)
                .results(List.of(item))
                .build();
        restTemplate.postForEntity(
                apiUrl("/analytics/test-case-results"), jsonEntity(request), BatchWriteResponseDto.class);

        // Filter by specific runId
        var response = restTemplate.exchange(
                apiUrl("/analytics/test-case-results?filter=suiteId:eq:" + testSuiteId + "&filter=runId:eq:"
                        + anotherRunId),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<CursorPageResponseDto<Object>>() {});

        assertThat(response.getBody().getContent()).hasSize(1);
    }

    @Test
    @DisplayName("Should filter by testCaseName with contains operator")
    void shouldFilterByTestCaseNameContains() {
        insertResults(3); // case-0, case-1, case-2

        var response = restTemplate.exchange(
                apiUrl("/analytics/test-case-results?filter=suiteId:eq:" + testSuiteId
                        + "&filter=testCaseName:co:case-1"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<CursorPageResponseDto<Object>>() {});

        assertThat(response.getBody().getContent()).hasSize(1);
    }

    @Test
    @DisplayName("Should combine multiple filters")
    void shouldCombineMultipleFilters() {
        insertResults(3); // all SUCCESS
        insertResultWithStatus(ExecutionStatus.FAILED);

        var response = restTemplate.exchange(
                apiUrl("/analytics/test-case-results?filter=suiteId:eq:" + testSuiteId
                        + "&filter=executionStatus:eq:SUCCESS&filter=runId:eq:" + testSuiteRunId),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<CursorPageResponseDto<Object>>() {});

        assertThat(response.getBody().getContent()).hasSize(3);
    }

    @Test
    @DisplayName("Should reject unknown filter field with 400")
    void shouldRejectUnknownFilterField() {
        var response = restTemplate.exchange(
                apiUrl("/analytics/test-case-results?filter=suiteId:eq:" + testSuiteId
                        + "&filter=unknownField:eq:value"),
                HttpMethod.GET,
                null,
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("Should filter by JSONB testCaseData path with eq operator")
    void shouldFilterByJsonbPathEq() {
        insertResultWithTestCaseData("exact-match");
        insertResultWithTestCaseData("other-value");

        var response = restTemplate.exchange(
                apiUrl("/analytics/test-case-results?filter=suiteId:eq:" + testSuiteId
                        + "&filter=testCaseData.prompt:eq:exact-match"),
                HttpMethod.GET,
                null,
                new ParameterizedTypeReference<CursorPageResponseDto<Object>>() {});

        assertThat(response.getBody().getContent()).hasSize(1);
    }

    @Test
    @DisplayName("Should reject invalid cursor with 400")
    void shouldRejectInvalidCursor() {
        var response = restTemplate.exchange(
                apiUrl("/analytics/test-case-results?filter=suiteId:eq:" + testSuiteId + "&cursor=invalidcursor"),
                HttpMethod.GET,
                null,
                String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    // --- helpers ---

    private void insertResults(int count) {
        List<TestCaseRunResultItemDto> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            items.add(buildItem(UUID.randomUUID(), "case-" + i, 0, ExecutionStatus.SUCCESS, "prompt-" + i));
        }
        BatchWriteRequestDto request = BatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(testSuiteRunId)
                .results(items)
                .build();
        restTemplate.postForEntity(
                apiUrl("/analytics/test-case-results"), jsonEntity(request), BatchWriteResponseDto.class);
    }

    private void insertResultWithStatus(ExecutionStatus status) {
        TestCaseRunResultItemDto item = buildItem(UUID.randomUUID(), "case-status", 0, status, "prompt");
        BatchWriteRequestDto request = BatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(testSuiteRunId)
                .results(List.of(item))
                .build();
        restTemplate.postForEntity(
                apiUrl("/analytics/test-case-results"), jsonEntity(request), BatchWriteResponseDto.class);
    }

    private void insertResultWithTestCaseData(String promptValue) {
        ObjectNode data = JsonNodeFactory.instance.objectNode().put("prompt", promptValue);
        TestCaseRunResultItemDto item = TestCaseRunResultItemDto.builder()
                .testCaseId(UUID.randomUUID())
                .testCaseName("case-data")
                .runIndex(0)
                .testCaseData(data)
                .responseStatusCode(200)
                .executionInfo(ExecutionInfoRequestDto.builder()
                        .status(ExecutionStatus.SUCCESS)
                        .startedAt(1739750400000L)
                        .completedAt(1739750401000L)
                        .build())
                .build();
        BatchWriteRequestDto request = BatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(testSuiteRunId)
                .results(List.of(item))
                .build();
        restTemplate.postForEntity(
                apiUrl("/analytics/test-case-results"), jsonEntity(request), BatchWriteResponseDto.class);
    }

    private TestCaseRunResultItemDto buildItem(
            UUID testCaseId, String name, int runIndex, ExecutionStatus status, String prompt) {
        ObjectNode data = JsonNodeFactory.instance.objectNode().put("prompt", prompt);
        return TestCaseRunResultItemDto.builder()
                .testCaseId(testCaseId)
                .testCaseName(name)
                .runIndex(runIndex)
                .testCaseData(data)
                .responseStatusCode(200)
                .executionInfo(ExecutionInfoRequestDto.builder()
                        .status(status)
                        .startedAt(1739750400000L)
                        .completedAt(1739750401234L)
                        .traceId("trace")
                        .build())
                .build();
    }
}
