package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.service.domain.dto.analytics.BatchWriteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.BatchWriteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.ExecutionInfoRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.ResultCountResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.TestCaseRunResultItemDto;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@DisplayName("Analytics Result Count Tests")
public abstract class AnalyticsResultCountFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    private UUID testSuiteId;
    private UUID testSuiteRunId;

    @BeforeEach
    void setUp() {
        analyticsTestDataHelper.cleanupResults();
        testSuiteId = metaTestDataHelper.createTestSuite("Count Suite").getId();
        testSuiteRunId = metaTestDataHelper.createTestSuiteRun(testSuiteId).getId();
    }

    @Test
    @DisplayName("Should count results with suiteId filter")
    void shouldCountResults() {
        insertResults(3);

        var response = restTemplate.getForEntity(
                apiUrl("/analytics/test-case-results/count?filter=suiteId:eq:" + testSuiteId),
                ResultCountResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getCount()).isEqualTo(3);
    }

    @Test
    @DisplayName("Should return 0 count when no results match")
    void shouldReturnZeroCount() {
        var response = restTemplate.getForEntity(
                apiUrl("/analytics/test-case-results/count?filter=suiteId:eq:" + UUID.randomUUID()),
                ResultCountResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getCount()).isEqualTo(0);
    }

    @Test
    @DisplayName("Should count results with additional executionStatus filter")
    void shouldCountWithExecutionStatusFilter() {
        insertResults(3); // all SUCCESS
        insertResultWithStatus(ExecutionStatus.FAILED);

        var response = restTemplate.getForEntity(
                apiUrl("/analytics/test-case-results/count?filter=suiteId:eq:" + testSuiteId
                        + "&filter=executionStatus:eq:FAILED"),
                ResultCountResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().getCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("Should reject missing suiteId filter with 400")
    void shouldRejectMissingSuiteIdFilter() {
        var response = restTemplate.getForEntity(apiUrl("/analytics/test-case-results/count"), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    private void insertResultWithStatus(ExecutionStatus status) {
        ObjectNode data = JsonNodeFactory.instance.objectNode().put("prompt", "prompt");
        TestCaseRunResultItemDto item = TestCaseRunResultItemDto.builder()
                .testCaseId(UUID.randomUUID())
                .testCaseName("case-status")
                .runIndex(0)
                .testCaseData(data)
                .responseStatusCode(200)
                .executionInfo(ExecutionInfoRequestDto.builder()
                        .status(status)
                        .startedAt(1739750400000L)
                        .completedAt(1739750401234L)
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

    private void insertResults(int count) {
        List<TestCaseRunResultItemDto> items = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            ObjectNode data = JsonNodeFactory.instance.objectNode().put("prompt", "prompt-" + i);
            items.add(TestCaseRunResultItemDto.builder()
                    .testCaseId(UUID.randomUUID())
                    .testCaseName("case-" + i)
                    .runIndex(0)
                    .testCaseData(data)
                    .responseStatusCode(200)
                    .executionInfo(ExecutionInfoRequestDto.builder()
                            .status(ExecutionStatus.SUCCESS)
                            .startedAt(1739750400000L)
                            .completedAt(1739750401234L)
                            .build())
                    .build());
        }
        BatchWriteRequestDto request = BatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(testSuiteRunId)
                .results(items)
                .build();
        restTemplate.postForEntity(
                apiUrl("/analytics/test-case-results"), jsonEntity(request), BatchWriteResponseDto.class);
    }
}
