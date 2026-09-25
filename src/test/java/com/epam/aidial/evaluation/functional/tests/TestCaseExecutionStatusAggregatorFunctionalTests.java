package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.EvalSummaryFixture;
import com.epam.aidial.evaluation.query.service.metricscore.TestCaseExecutionStatusAggregator;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("TestCaseExecutionStatusAggregator tests")
public abstract class TestCaseExecutionStatusAggregatorFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private TestCaseExecutionStatusAggregator aggregator;

    @Test
    @DisplayName("a test case whose rows are all SUCCESS aggregates to SUCCESS")
    void allSuccessRowsAggregateToSuccess() {
        UUID suiteId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();
        UUID testCaseId = UUID.randomUUID();
        long now = System.currentTimeMillis();

        for (int turnIndex = 0; turnIndex < 2; turnIndex++) {
            analyticsTestDataHelper.createEvalSummary(EvalSummaryFixture.builder()
                    .suiteId(suiteId)
                    .runId(runId)
                    .computationId(computationId)
                    .testCaseId(testCaseId)
                    .testCaseName("TC-1")
                    .createdAtMs(now)
                    .turnIndex(turnIndex)
                    .executionStatus(ExecutionStatus.SUCCESS.name())
                    .build());
        }

        Map<UUID, ExecutionStatus> result = aggregator.aggregate(runId, computationId, List.of(testCaseId));

        assertThat(result).containsEntry(testCaseId, ExecutionStatus.SUCCESS);
    }

    @Test
    @DisplayName("a test case with one FAILED row among otherwise SUCCESS rows aggregates to FAILED")
    void oneFailedRowAggregatesToFailed() {
        UUID suiteId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();
        UUID testCaseId = UUID.randomUUID();
        long now = System.currentTimeMillis();

        analyticsTestDataHelper.createEvalSummary(EvalSummaryFixture.builder()
                .suiteId(suiteId)
                .runId(runId)
                .computationId(computationId)
                .testCaseId(testCaseId)
                .testCaseName("TC-2")
                .createdAtMs(now)
                .turnIndex(0)
                .executionStatus(ExecutionStatus.SUCCESS.name())
                .build());
        analyticsTestDataHelper.createEvalSummary(EvalSummaryFixture.builder()
                .suiteId(suiteId)
                .runId(runId)
                .computationId(computationId)
                .testCaseId(testCaseId)
                .testCaseName("TC-2")
                .createdAtMs(now)
                .turnIndex(1)
                .executionStatus(ExecutionStatus.TIMEOUT.name())
                .build());

        Map<UUID, ExecutionStatus> result = aggregator.aggregate(runId, computationId, List.of(testCaseId));

        assertThat(result).containsEntry(testCaseId, ExecutionStatus.FAILED);
    }

    @Test
    @DisplayName("an empty testCaseIds list returns an empty map without querying")
    void emptyTestCaseIdsReturnsEmptyMap() {
        UUID runId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();

        Map<UUID, ExecutionStatus> result = aggregator.aggregate(runId, computationId, List.of());

        assertThat(result).isEmpty();
    }
}
