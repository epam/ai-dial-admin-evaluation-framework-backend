package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.MetaTestDataHelper;
import com.epam.aidial.evaluation.service.domain.dto.analytics.BatchWriteRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.BatchWriteResponseDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.ExecutionInfoRequestDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.TestCaseRunResultItemDto;
import com.epam.aidial.evaluation.service.domain.dto.analytics.TestCaseRunResultResponseDto;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.node.JsonNodeFactory;
import tools.jackson.databind.node.ObjectNode;

@DisplayName("Analytics Result Get By ID Tests")
public abstract class AnalyticsResultGetByIdFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private MetaTestDataHelper metaTestDataHelper;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    private UUID testSuiteId;
    private UUID testSuiteRunId;

    @BeforeEach
    void setUp() {
        analyticsTestDataHelper.cleanupResults();
        testSuiteId = metaTestDataHelper.createTestSuite("GetById Suite").getId();
        testSuiteRunId = metaTestDataHelper.createTestSuiteRun(testSuiteId).getId();
    }

    @Test
    @DisplayName("Should get existing result by ID")
    void shouldGetExistingResult() {
        // Insert a result
        TestCaseRunResultItemDto item = buildItem();
        BatchWriteRequestDto request = BatchWriteRequestDto.builder()
                .testSuiteId(testSuiteId)
                .testSuiteRunId(testSuiteRunId)
                .results(List.of(item))
                .build();
        restTemplate.postForEntity(
                apiUrl("/analytics/test-case-results"), jsonEntity(request), BatchWriteResponseDto.class);

        // Get the inserted ID
        UUID insertedId = analyticsTestDataHelper.findAnyResultId().orElseThrow();

        var response = restTemplate.getForEntity(
                apiUrl("/analytics/test-case-results/" + insertedId), TestCaseRunResultResponseDto.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getId()).isEqualTo(insertedId);
        assertThat(response.getBody().getTestCaseName()).isEqualTo("case-1");
        assertThat(response.getBody().getExecutionInfo()).isNotNull();
        assertThat(response.getBody().getExecutionInfo().getDurationMs()).isEqualTo(1234L);
    }

    @Test
    @DisplayName("Should return 404 for non-existent ID")
    void shouldReturn404ForNonExistentId() {
        var response =
                restTemplate.getForEntity(apiUrl("/analytics/test-case-results/" + UUID.randomUUID()), String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    private TestCaseRunResultItemDto buildItem() {
        ObjectNode data = JsonNodeFactory.instance.objectNode().put("prompt", "test");
        return TestCaseRunResultItemDto.builder()
                .testCaseId(UUID.randomUUID())
                .testCaseName("case-1")
                .runIndex(0)
                .testCaseData(data)
                .responseStatusCode(200)
                .executionInfo(ExecutionInfoRequestDto.builder()
                        .status(ExecutionStatus.SUCCESS)
                        .startedAt(1739750400000L)
                        .completedAt(1739750401234L)
                        .build())
                .build();
    }
}
