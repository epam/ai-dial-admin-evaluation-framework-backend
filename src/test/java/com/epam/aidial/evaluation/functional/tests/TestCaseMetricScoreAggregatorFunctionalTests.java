package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.EvalSummaryFixture;
import com.epam.aidial.evaluation.query.service.metricscore.TestCaseMetricScoreAggregator;
import com.epam.aidial.evaluation.service.domain.dto.analytics.TestCaseMetricScoreAggregatedBatchWriteItemDto;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("TestCaseMetricScoreAggregator tests")
public abstract class TestCaseMetricScoreAggregatorFunctionalTests extends BaseFunctionalTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private TestCaseMetricScoreAggregator aggregator;

    @Test
    @DisplayName("aggregates a metric across a test case's 8 rows (2 turns x 2 requests x 2 reruns), "
            + "computing avg/min/max/count over only the rows where it fired")
    void aggregatesOnlyOverRowsWhereMetricFired() {
        UUID suiteId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();
        UUID testCaseId = UUID.randomUUID();
        long now = System.currentTimeMillis();

        // MetricA fires only on requestIndex == 0 rows (4 of the 8), scoring 0.5, 0.5, 0.2, 0.2.
        // The other 4 rows (requestIndex == 1) have no metric_values at all.
        double[] scoresOnFiringRows = {0.5, 0.5, 0.2, 0.2};
        int scoreIndex = 0;
        for (int runIndex = 0; runIndex < 2; runIndex++) {
            for (int requestIndex = 0; requestIndex < 2; requestIndex++) {
                for (int turnIndex = 0; turnIndex < 2; turnIndex++) {
                    String metricValuesJson = requestIndex == 0
                            ? "{\"MetricA\":{\"score\":" + scoresOnFiringRows[scoreIndex++ % 4] + "}}"
                            : "{}";
                    analyticsTestDataHelper.createEvalSummary(EvalSummaryFixture.builder()
                            .suiteId(suiteId)
                            .runId(runId)
                            .computationId(computationId)
                            .testCaseId(testCaseId)
                            .testCaseName("TC-1")
                            .createdAtMs(now)
                            .runIndex(runIndex)
                            .requestIndex(requestIndex)
                            .turnIndex(turnIndex)
                            .metricValuesJson(metricValuesJson)
                            .build());
                }
            }
        }

        List<TestCaseMetricScoreAggregatedBatchWriteItemDto> result =
                aggregator.aggregate(runId, computationId, List.of(testCaseId));

        assertThat(result).hasSize(1);
        TestCaseMetricScoreAggregatedBatchWriteItemDto item = result.getFirst();
        assertThat(item.getTestCaseId()).isEqualTo(testCaseId);
        JsonNode metricA = metricScoresNode(item).get("MetricA.score");
        assertThat(metricA.get("count").asInt()).isEqualTo(4);
        assertThat(metricA.get("avg").asDouble()).isEqualTo(0.35);
        assertThat(metricA.get("min").asDouble()).isEqualTo(0.2);
        assertThat(metricA.get("max").asDouble()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("an explicit JSON null output value is treated as 0, not excluded like an absent metric")
    void nullOutputValueIsCoalescedToZero() {
        UUID suiteId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();
        UUID testCaseId = UUID.randomUUID();
        long now = System.currentTimeMillis();

        // "Exact Match" errored on this row (null output) while "Ragas: Answer Relevancy" scored 0.0 —
        // the exact shape a provider error surfaces as. A metric absent entirely (e.g. Ragas on the second
        // row) must still stay excluded from its own stats, unaffected by this row's null.
        analyticsTestDataHelper.createEvalSummary(EvalSummaryFixture.builder()
                .suiteId(suiteId)
                .runId(runId)
                .computationId(computationId)
                .testCaseId(testCaseId)
                .testCaseName("TC-3")
                .createdAtMs(now)
                .runIndex(0)
                .metricValuesJson(
                        "{\"Exact Match\":{\"exact_match\":null},\"Ragas: Answer Relevancy\":{\"score\":0.0}}")
                .build());
        analyticsTestDataHelper.createEvalSummary(EvalSummaryFixture.builder()
                .suiteId(suiteId)
                .runId(runId)
                .computationId(computationId)
                .testCaseId(testCaseId)
                .testCaseName("TC-3")
                .createdAtMs(now)
                .runIndex(1)
                .metricValuesJson("{\"Exact Match\":{\"exact_match\":1.0}}")
                .build());

        List<TestCaseMetricScoreAggregatedBatchWriteItemDto> result =
                aggregator.aggregate(runId, computationId, List.of(testCaseId));

        assertThat(result).hasSize(1);
        JsonNode metricScores = metricScoresNode(result.getFirst());
        JsonNode exactMatch = metricScores.get("Exact Match.exact_match");
        // The null row counts as a real (failing) sample: count 2, not 1; avg pulled down to 0.5, not 1.0.
        assertThat(exactMatch.get("count").asInt()).isEqualTo(2);
        assertThat(exactMatch.get("avg").asDouble()).isEqualTo(0.5);
        assertThat(exactMatch.get("min").asDouble()).isEqualTo(0.0);
        assertThat(exactMatch.get("max").asDouble()).isEqualTo(1.0);
        // "Ragas: Answer Relevancy" is absent from the second row entirely — that row must not contribute
        // a zero/null sample of its own, unlike the null-valued "Exact Match" on the first row.
        JsonNode ragas = metricScores.get("Ragas: Answer Relevancy.score");
        assertThat(ragas.get("count").asInt()).isEqualTo(1);
        assertThat(ragas.get("avg").asDouble()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("a metric that never fired for a test case is absent from its metric_scores map")
    void metricNeverFiredIsOmittedFromMap() {
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
                .metricValuesJson("{\"MetricA\":{\"score\":0.7}}")
                .build());

        List<TestCaseMetricScoreAggregatedBatchWriteItemDto> result =
                aggregator.aggregate(runId, computationId, List.of(testCaseId));

        assertThat(result).hasSize(1);
        JsonNode metricScores = metricScoresNode(result.getFirst());
        assertThat(metricScores.has("MetricA.score")).isTrue();
        assertThat(metricScores.has("MetricB.score")).isFalse();
    }

    @Test
    @DisplayName("a test case with no samples for any metric field produces no result row")
    void testCaseWithNoSamplesForAnyMetricIsAbsentFromResult() {
        UUID runId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();
        UUID testCaseId = UUID.randomUUID();

        List<TestCaseMetricScoreAggregatedBatchWriteItemDto> result =
                aggregator.aggregate(runId, computationId, List.of(testCaseId));

        assertThat(result).isEmpty();
    }

    private static JsonNode metricScoresNode(TestCaseMetricScoreAggregatedBatchWriteItemDto item) {
        try {
            return OBJECT_MAPPER.readTree(item.getMetricScores());
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Invalid metric_scores JSON: " + item.getMetricScores(), e);
        }
    }
}
