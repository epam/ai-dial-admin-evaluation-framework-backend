package com.epam.aidial.evaluation.service.domain.job;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.client.metricprovider.dto.EvaluationResponseDto;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.runner.model.TestCaseRunResult;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@DisplayName("MetricRowEvaluator")
class MetricRowEvaluatorTest {

    // computeMetricEvalDurationMs has no dependency on the injected collaborators, so a nulled-out
    // instance is sufficient here; the collaborator-dependent behavior (dispatch, conditions, timeout,
    // status mapping) is exercised end-to-end through InProcessMetricEvaluationExecutorTest, which wires
    // a real MetricRowEvaluator instance.
    private final MetricRowEvaluator evaluator = new MetricRowEvaluator(null, null, null, null, null, null);

    @Test
    @DisplayName("computeMetricEvalDurationMs excludes ConditionError entries and defaults to 0")
    void computeMetricEvalDurationMs_excludesConditionErrorsAndDefaultsToZero() {
        EvaluationResponseDto response = EvaluationResponseDto.builder().build();

        Map<String, TsmdEvaluationResult> mixed = Map.of(
                "successMetric", new TsmdEvaluationResult.Success(response, List.of(), 100L),
                "failedMetric", new TsmdEvaluationResult.Failure(new RuntimeException("boom"), List.of(), 300L),
                "conditionErrorMetric", new TsmdEvaluationResult.ConditionError("bad condition", List.of()));

        assertThat(evaluator.computeMetricEvalDurationMs(mixed))
                .as("sum must be over Success/Failure only: 100 + 300")
                .isEqualTo(400L);

        assertThat(evaluator.computeMetricEvalDurationMs(Map.of()))
                .as("no dispatched TSMDs defaults to 0")
                .isEqualTo(0L);
    }

    @Test
    @DisplayName("buildFailedItem marks the EvalSummary FAILED with a wholesale error info, regardless of the"
            + " row's own (SUCCESS) executionStatus")
    void buildFailedItem_marksFailedWithWholesaleErrorInfo() {
        // buildFailedItem only touches objectMapper (createObjectNode/put) plus the private buildItem
        // helper that reads TestCaseRunResult/MetricEvaluationContext fields — no other collaborator is
        // exercised, so a nulled-out instance except for a real ObjectMapper is sufficient here.
        MetricRowEvaluator withMapper = new MetricRowEvaluator(null, null, new ObjectMapper(), null, null, null);
        TestCaseRunResult row = TestCaseRunResult.builder()
                .id(UUID.randomUUID())
                .testCaseId(UUID.randomUUID())
                .executionStatus(ExecutionStatus.SUCCESS)
                .build();
        MetricEvaluationContext context = MetricEvaluationContext.builder()
                .computationId(UUID.randomUUID())
                .testSuiteRunId(UUID.randomUUID())
                .testSuiteId(UUID.randomUUID())
                .build();

        MetricRowEvaluationResult result = withMapper.buildFailedItem(row, context, "boom");

        assertThat(result.hasError()).isTrue();
        assertThat(result.tsmdResults()).isEmpty();
        assertThat(result.item().getExecutionStatus()).isEqualTo(ExecutionStatus.FAILED);
        assertThat(result.item().getMetricValues().isEmpty()).isTrue();
        assertThat(result.item().getMetricInfos().get("error").asString()).isEqualTo("boom");
    }
}
