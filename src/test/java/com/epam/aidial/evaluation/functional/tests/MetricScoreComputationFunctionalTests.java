package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.epam.aidial.evaluation.configuration.JsonMapperConfiguration;
import com.epam.aidial.evaluation.data.db.analytics.model.MetricScoreResult;
import com.epam.aidial.evaluation.data.db.analytics.repository.MetricScoreResultRepository;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.query.service.metricscore.MetricScoreComputationExecutor;
import com.epam.aidial.evaluation.runner.dto.overallscore.CustomFunction;
import com.epam.aidial.evaluation.runner.dto.overallscore.OverallScoreDefinition;
import com.epam.aidial.evaluation.runner.dto.overallscore.WeightedMean;
import com.epam.aidial.evaluation.runner.dto.overallscore.WeightedMetric;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.service.domain.job.MetricScoreComputationContext;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * End-to-end Phase-3 metric-score computation over a run's persisted eval summaries. Exercises the
 * full DSL path including {@code percentile_cont} over a JSONB-extracted numeric metric value on real
 * Postgres. Results are read via the {@code metric_score_results} Query DSL entity (see
 * {@code MetricScoreResultStructuredQueryFunctionalTests}).
 */
@DisplayName("Metric Score Computation (Phase 3) Functional Tests")
public abstract class MetricScoreComputationFunctionalTests extends BaseFunctionalTest {

    private static final ObjectMapper OBJECT_MAPPER = new JsonMapperConfiguration().objectMapper();
    private static final long COMPUTED_AT_MS = 1_700_000_600_000L;
    private static final String OUTPUT_SCHEMA = "{\"properties\":{\"score\":{\"type\":\"number\"}}}";
    private static final String CLASSIFIER_OUTPUT_SCHEMA =
            "{\"properties\":{\"label\":{\"type\":\"number\"},\"probability\":{\"type\":\"number\"}}}";

    /**
     * A self-contained custom overall averaging exactly one of two metric columns (Relevancy), run-scoped
     * by the {@code :runId}/{@code :computationId} params the executor always supplies. This is the same
     * JSON a client would PUT as a suite's {@code overallScore} (double-colon {@code metric::<name>::<field>}).
     */
    private static final String CUSTOM_OVERALL_RELEVANCY = "{\"entity\":\"eval_summaries\",\"mode\":\"aggregate\","
            + "\"filter\":{\"op\":\"and\",\"args\":["
            + "{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"test_suite_run_id\"},"
            + "{\"type\":\"param\",\"name\":\"runId\"}]},"
            + "{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"computation_id\"},"
            + "{\"type\":\"param\",\"name\":\"computationId\"}]}]},"
            + "\"select\":[{\"expr\":{\"type\":\"fn\",\"name\":\"avg\","
            + "\"args\":[{\"type\":\"field\",\"name\":\"metric::Relevancy::score\"}]},\"as\":\"value\"}]}";

    /**
     * A custom overall computing ROC AUC over a classifier metric's {@code label} (ground truth 0/1) and
     * {@code probability} (predicted score) outputs, run-scoped the same way as {@link #CUSTOM_OVERALL_RELEVANCY}.
     */
    private static final String CUSTOM_OVERALL_ROC_AUC = "{\"entity\":\"eval_summaries\",\"mode\":\"aggregate\","
            + "\"filter\":{\"op\":\"and\",\"args\":["
            + "{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"test_suite_run_id\"},"
            + "{\"type\":\"param\",\"name\":\"runId\"}]},"
            + "{\"op\":\"eq\",\"args\":[{\"type\":\"field\",\"name\":\"computation_id\"},"
            + "{\"type\":\"param\",\"name\":\"computationId\"}]}]},"
            + "\"select\":[{\"expr\":{\"type\":\"fn\",\"name\":\"roc_auc\",\"args\":["
            + "{\"type\":\"field\",\"name\":\"metric::Classifier::label\"},"
            + "{\"type\":\"field\",\"name\":\"metric::Classifier::probability\"}]},\"as\":\"value\"}]}";

    @Autowired
    private MetricScoreComputationExecutor executor;

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

        assertThat(results).allSatisfy(result -> {
            assertThat(result.getComputationId()).isEqualTo(computationId);
            // The suite is denormalized onto every result, and each carries a compute timestamp.
            assertThat(result.getTestSuiteId()).isEqualTo(suiteId);
            assertThat(result.getComputedAtMs()).isNotNull().isPositive();
        });
        // All results of one computation share a single compute timestamp.
        assertThat(results)
                .extracting(MetricScoreResult::getComputedAtMs)
                .containsOnly(results.getFirst().getComputedAtMs());
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

    @Test
    @DisplayName("custom overall referencing one of two metrics computes that metric's average end-to-end")
    void computesCustomOverallForOneOfTwoMetrics() {
        final UUID suiteId = UUID.randomUUID();
        final UUID runId = UUID.randomUUID();
        final UUID computationId = UUID.randomUUID();
        final long createdAt = 1_700_000_000_000L;
        final long computedAt = 1_700_000_500_000L;

        // Relevancy avg = (0.0+0.5+1.0)/3 = 0.5; Accuracy avg = (0.6+0.7+0.8)/3 = 0.7.
        seedTwoMetricRun(suiteId, runId, computationId, createdAt, computedAt);

        executor.execute(context(suiteId, runId, computationId, customFunction(CUSTOM_OVERALL_RELEVANCY)));

        final List<MetricScoreResult> results = resultRepository.findByRunAndComputation(runId, computationId);
        // 5 per-metric stats x 2 fields + the custom overall (computed for any metric count).
        assertThat(results).hasSize(11);
        // overall is Relevancy's average alone (0.5) — NOT Accuracy (0.7) and NOT the two-metric mean (0.6).
        assertThat(value(results, "overall", "overall")).isCloseTo(0.5, within(1e-6));
    }

    @Test
    @DisplayName("custom overall computes ROC AUC over a classifier's label/probability outputs end-to-end")
    void computesCustomOverallRocAuc() {
        final UUID suiteId = UUID.randomUUID();
        final UUID runId = UUID.randomUUID();
        final UUID computationId = UUID.randomUUID();
        final long createdAt = 1_700_000_000_000L;
        final long computedAt = 1_700_000_500_000L;

        // label/probability pairs: (0, 0.1), (0, 0.4), (1, 0.35), (1, 0.8) -> AUC = 0.75 (one discordant pair).
        seedClassifierRun(suiteId, runId, computationId, createdAt, computedAt);

        executor.execute(context(suiteId, runId, computationId, customFunction(CUSTOM_OVERALL_ROC_AUC)));

        final List<MetricScoreResult> results = resultRepository.findByRunAndComputation(runId, computationId);
        assertThat(value(results, "overall", "overall")).isCloseTo(0.75, within(1e-9));
    }

    @Test
    @DisplayName(
            "weighted mean coalesces a metric missing from the run's data to zero instead of nulling the whole overall")
    void computesWeightedMeanWithMissingMetricAsZero() {
        final UUID suiteId = UUID.randomUUID();
        final UUID runId = UUID.randomUUID();
        final UUID computationId = UUID.randomUUID();
        final long createdAt = 1_700_000_000_000L;
        final long computedAt = 1_700_000_500_000L;

        // Only Relevancy is present in the run's data (avg = 0.5); "Ghost" is never seeded.
        seedRun(suiteId, runId, computationId, createdAt, computedAt);
        final WeightedMean weightedMean = new WeightedMean(List.of(
                new WeightedMetric("Relevancy", "score", new BigDecimal("1.0")),
                new WeightedMetric("Ghost", "score", new BigDecimal("1.0"))));

        executor.execute(context(suiteId, runId, computationId, weightedMean));

        final List<MetricScoreResult> results = resultRepository.findByRunAndComputation(runId, computationId);
        // (1*0.5 + 1*0) / (1+1) = 0.25 — the missing "Ghost" term is coalesced to 0, not left NULL.
        assertThat(value(results, "overall", "overall")).isCloseTo(0.25, within(1e-9));
    }

    private void seedClassifierRun(UUID suiteId, UUID runId, UUID computationId, long createdAt, long computedAt) {
        analyticsTestDataHelper.createRunMetricSnapshot(
                runId, computationId, "Classifier", CLASSIFIER_OUTPUT_SCHEMA, computedAt);
        seedClassifierSummary(suiteId, runId, computationId, "case-a", createdAt, 0, 0.1);
        seedClassifierSummary(suiteId, runId, computationId, "case-b", createdAt, 0, 0.4);
        seedClassifierSummary(suiteId, runId, computationId, "case-c", createdAt, 1, 0.35);
        seedClassifierSummary(suiteId, runId, computationId, "case-d", createdAt, 1, 0.8);
    }

    private void seedClassifierSummary(
            UUID suiteId,
            UUID runId,
            UUID computationId,
            String caseId,
            long createdAt,
            int label,
            double probability) {
        analyticsTestDataHelper.createEvalSummary(
                suiteId,
                runId,
                computationId,
                caseId,
                ExecutionStatus.SUCCESS.name(),
                100L,
                createdAt,
                "{}",
                "{\"Classifier\":{\"label\":" + label + ",\"probability\":" + probability + "}}");
    }

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
            UUID suiteId, UUID runId, UUID computationId, OverallScoreDefinition overallScoreDefinition) {
        return MetricScoreComputationContext.builder()
                .testSuiteRunId(runId)
                .testSuiteId(suiteId)
                .computationId(computationId)
                .overallScoreDefinition(overallScoreDefinition)
                .computedAtMs(COMPUTED_AT_MS)
                .cancellationSignal(new AtomicBoolean(false))
                .build();
    }

    private static CustomFunction customFunction(String expressionJson) {
        return new CustomFunction(OBJECT_MAPPER.readValue(expressionJson, new TypeReference<Map<String, Object>>() {}));
    }

    private static double value(List<MetricScoreResult> results, String scoreName, String metricName) {
        return results.stream()
                .filter(r -> scoreName.equals(r.getMetricScoreName()) && metricName.equals(r.getMetricName()))
                .map(MetricScoreResult::getValue)
                .findFirst()
                .orElseThrow(() -> new AssertionError("missing result " + scoreName + "/" + metricName));
    }
}
