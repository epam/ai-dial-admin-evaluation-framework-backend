package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
import com.epam.aidial.evaluation.data.db.analytics.model.MetricScoreResult;
import com.epam.aidial.evaluation.data.db.analytics.repository.MetricScoreResultRepository;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.service.domain.job.MetricScoreComputation;
import com.epam.aidial.evaluation.service.domain.job.MetricScoreComputationContext;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * End-to-end Phase-3 metric-score computation over a run's persisted eval summaries. Exercises the
 * full DSL path including {@code percentile_cont} over a JSONB-extracted numeric metric value on real
 * Postgres. Results are read via the {@code metric_score_results} Query DSL entity (see
 * {@code MetricScoreResultStructuredQueryFunctionalTests}).
 */
@DisplayName("Metric Score Computation (Phase 3) Functional Tests")
public abstract class MetricScoreComputationFunctionalTests extends BaseFunctionalTest {

    private static final String OUTPUT_SCHEMA = "{\"properties\":{\"score\":{\"type\":\"number\"}}}";

    @Autowired
    private MetricScoreComputation executor;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private MetricScoreResultRepository resultRepository;

    @Test
    @DisplayName("computes AVG/P10/P90/MIN/MAX per metric field plus overall, under the run's computation")
    void computesStatisticsForRun() {
        final UUID suiteId = UUID.randomUUID();
        final UUID runId = UUID.randomUUID();
        final UUID computationId = UUID.randomUUID();
        final long createdAt = 1_700_000_000_000L;
        final long computedAt = 1_700_000_500_000L;

        seedRun(suiteId, runId, computationId, createdAt, computedAt);

        executor.execute(context(suiteId, runId, computationId));

        final List<MetricScoreResult> results = resultRepository.findByRunAndComputation(runId, computationId);
        // 5 DEFAULT statistics over the single metric field + the DSL-computed overall.
        assertThat(results).hasSize(6);
        assertThat(value(results, "AVG", "Relevancy.score")).isEqualTo(0.5);
        assertThat(value(results, "MIN", "Relevancy.score")).isEqualTo(0.0);
        assertThat(value(results, "MAX", "Relevancy.score")).isEqualTo(1.0);
        assertThat(value(results, "P10", "Relevancy.score")).isCloseTo(0.1, within(1e-6));
        assertThat(value(results, "P90", "Relevancy.score")).isCloseTo(0.9, within(1e-6));
        // overall = unweighted mean of the per-metric averages; one metric whose AVG is 0.5.
        assertThat(value(results, "overall", "overall")).isCloseTo(0.5, within(1e-6));

        assertThat(results)
                .allSatisfy(result -> assertThat(result.getComputationId()).isEqualTo(computationId));
    }

    @Test
    @DisplayName("computes per-metric statistics but no default overall when the run has multiple metric fields")
    void skipsDefaultOverallForMultipleMetrics() {
        final UUID suiteId = UUID.randomUUID();
        final UUID runId = UUID.randomUUID();
        final UUID computationId = UUID.randomUUID();
        final long createdAt = 1_700_000_000_000L;
        final long computedAt = 1_700_000_500_000L;

        seedTwoMetricRun(suiteId, runId, computationId, createdAt, computedAt);

        executor.execute(context(suiteId, runId, computationId));

        final List<MetricScoreResult> results = resultRepository.findByRunAndComputation(runId, computationId);
        // 5 DEFAULT statistics x 2 metric fields, and NO overall (default overall is single-metric only).
        assertThat(results).hasSize(10);
        assertThat(results).extracting(MetricScoreResult::getMetricScoreName).doesNotContain("overall");
    }

    // A custom per-suite overall (a self-contained expression referencing the configured metric columns)
    // is future work: the dynamic `metric:<tsmd>:<field>` JSONB columns are not yet authorable through
    // the query schema, so an authored custom expression cannot translate end-to-end. The executor's
    // custom-overall branch (run with only the run-scoping params) is covered by the unit test
    // MetricScoreComputationExecutorTest#computesCustomOverallForMultipleMetrics.

    private void seedRun(UUID suiteId, UUID runId, UUID computationId, long createdAt, long computedAt) {
        analyticsTestDataHelper.createRunMetricSnapshot(runId, computationId, "Relevancy", OUTPUT_SCHEMA, computedAt);
        analyticsTestDataHelper.createEvalSummary(
                suiteId,
                runId,
                computationId,
                "case-a",
                ExecutionStatus.SUCCESS.name(),
                100L,
                createdAt,
                "{}",
                "{\"Relevancy\":{\"score\":0.0}}");
        analyticsTestDataHelper.createEvalSummary(
                suiteId,
                runId,
                computationId,
                "case-b",
                ExecutionStatus.SUCCESS.name(),
                100L,
                createdAt,
                "{}",
                "{\"Relevancy\":{\"score\":0.5}}");
        analyticsTestDataHelper.createEvalSummary(
                suiteId,
                runId,
                computationId,
                "case-c",
                ExecutionStatus.SUCCESS.name(),
                100L,
                createdAt,
                "{}",
                "{\"Relevancy\":{\"score\":1.0}}");
    }

    private void seedTwoMetricRun(UUID suiteId, UUID runId, UUID computationId, long createdAt, long computedAt) {
        analyticsTestDataHelper.createRunMetricSnapshot(runId, computationId, "Relevancy", OUTPUT_SCHEMA, computedAt);
        analyticsTestDataHelper.createRunMetricSnapshot(runId, computationId, "Accuracy", OUTPUT_SCHEMA, computedAt);
        seedTwoMetricSummary(suiteId, runId, computationId, "case-a", createdAt, 0.0, 0.6);
        seedTwoMetricSummary(suiteId, runId, computationId, "case-b", createdAt, 0.5, 0.7);
        seedTwoMetricSummary(suiteId, runId, computationId, "case-c", createdAt, 1.0, 0.8);
    }

    private void seedTwoMetricSummary(
            UUID suiteId,
            UUID runId,
            UUID computationId,
            String caseId,
            long createdAt,
            double relevancy,
            double accuracy) {
        analyticsTestDataHelper.createEvalSummary(
                suiteId,
                runId,
                computationId,
                caseId,
                ExecutionStatus.SUCCESS.name(),
                100L,
                createdAt,
                "{}",
                "{\"Relevancy\":{\"score\":" + relevancy + "},\"Accuracy\":{\"score\":" + accuracy + "}}");
    }

    private static MetricScoreComputationContext context(UUID suiteId, UUID runId, UUID computationId) {
        return context(suiteId, runId, computationId, null);
    }

    private static MetricScoreComputationContext context(
            UUID suiteId, UUID runId, UUID computationId, String overallExpression) {
        return MetricScoreComputationContext.builder()
                .testSuiteRunId(runId)
                .testSuiteId(suiteId)
                .computationId(computationId)
                .overallExpression(overallExpression)
                .cancellationSignal(new AtomicBoolean(false))
                .build();
    }

    private static double value(List<MetricScoreResult> results, String scoreName, String metricName) {
        return results.stream()
                .filter(r -> scoreName.equals(r.getMetricScoreName()) && metricName.equals(r.getMetricName()))
                .map(MetricScoreResult::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing result " + scoreName + "/" + metricName));
    }
}
