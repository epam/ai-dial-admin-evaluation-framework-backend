package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

import com.epam.aidial.evaluation.data.db.analytics.model.MetricScoreResult;
import com.epam.aidial.evaluation.experimental.query.model.ArrayExpr;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonNode;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonOp;
import com.epam.aidial.evaluation.experimental.query.model.FieldExpr;
import com.epam.aidial.evaluation.experimental.query.model.FilterNode;
import com.epam.aidial.evaluation.experimental.query.model.FnExpr;
import com.epam.aidial.evaluation.experimental.query.model.LogicalNode;
import com.epam.aidial.evaluation.experimental.query.model.LogicalOp;
import com.epam.aidial.evaluation.experimental.query.model.OffsetPage;
import com.epam.aidial.evaluation.experimental.query.model.OutputColumn;
import com.epam.aidial.evaluation.experimental.query.model.QueryMode;
import com.epam.aidial.evaluation.experimental.query.model.StructuredQuery;
import com.epam.aidial.evaluation.experimental.query.model.ValueExpr;
import com.epam.aidial.evaluation.experimental.query.model.ValueType;
import com.epam.aidial.evaluation.experimental.query.service.repository.MetricScoreResultQueryRepository;
import com.epam.aidial.evaluation.experimental.query.service.repository.QueryResultPage;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.service.domain.analytics.MetricScoreService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * End-to-end reads of the {@code metric_score_results} queryable entity via the unified Query DSL on
 * real Postgres: per-run/per-computation projection, {@code in} over computations (the cross-run
 * building block), aggregate rollups, and the entity's {@code computation_id eq "latest"} sentinel
 * resolution (scoped to the run's latest computation via {@code ComputationResolver}).
 */
@DisplayName("Structured Query → jOOQ translation (metric_score_results) Tests")
public abstract class MetricScoreResultStructuredQueryFunctionalTests extends BaseFunctionalTest {

    private static final String OUTPUT_SCHEMA = "{\"properties\":{\"score\":{\"type\":\"number\"}}}";

    @Autowired
    private MetricScoreResultQueryRepository queryRepository;

    @Autowired
    private MetricScoreService metricScoreService;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    private static MetricScoreResult result(
            UUID runId, UUID computationId, String scoreName, String metricName, double value) {
        return MetricScoreResult.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(runId)
                .computationId(computationId)
                .metricScoreName(scoreName)
                .metricName(metricName)
                .value(value)
                .build();
    }

    private static StructuredQuery rowQuery(FilterNode filter, List<OutputColumn> select) {
        return new StructuredQuery(
                "metric_score_results",
                filter,
                QueryMode.ROW,
                false,
                select,
                null,
                null,
                null,
                new OffsetPage(0, 100, false));
    }

    private static OutputColumn col(String field) {
        return new OutputColumn(new FieldExpr(field), null);
    }

    private static ComparisonNode eq(String field, String value) {
        return new ComparisonNode(ComparisonOp.EQ, List.of(new FieldExpr(field), new ValueExpr(ValueType.UUID, value)));
    }

    @Test
    @DisplayName("projects a run's metric-score results for an explicit computation")
    void projectsResultsForRunAndComputation() {
        UUID runId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();
        metricScoreService.saveAll(List.of(
                result(runId, computationId, "AVG", "Relevancy.score", 0.5),
                result(runId, computationId, "P90", "Relevancy.score", 0.9),
                result(runId, computationId, "overall", "overall", 0.5)));

        FilterNode filter = new LogicalNode(
                LogicalOp.AND,
                List.of(eq("test_suite_run_id", runId.toString()), eq("computation_id", computationId.toString())));

        QueryResultPage page = queryRepository.execute(
                rowQuery(filter, List.of(col("metric_score_name"), col("metric_name"), col("value"))));

        assertThat(page.rows()).hasSize(3);
        assertThat(page.rows())
                .extracting(row -> row.get("metric_score_name"))
                .containsExactlyInAnyOrder("AVG", "P90", "overall");
        Map<String, Object> overall = page.rows().stream()
                .filter(r -> "overall".equals(r.get("metric_score_name")))
                .findFirst()
                .orElseThrow();
        assertThat(((Number) overall.get("value")).doubleValue()).isEqualTo(0.5);
    }

    @Test
    @DisplayName("filters across computations with 'in' (the cross-run aggregation building block)")
    void filtersAcrossComputationsWithIn() {
        UUID runId = UUID.randomUUID();
        UUID computationA = UUID.randomUUID();
        UUID computationB = UUID.randomUUID();
        metricScoreService.saveAll(List.of(
                result(runId, computationA, "AVG", "Relevancy.score", 0.4),
                result(runId, computationB, "AVG", "Relevancy.score", 0.8)));

        FilterNode filter = new LogicalNode(
                LogicalOp.AND,
                List.of(
                        eq("test_suite_run_id", runId.toString()),
                        new ComparisonNode(
                                ComparisonOp.IN,
                                List.of(
                                        new FieldExpr("computation_id"),
                                        new ArrayExpr(List.of(
                                                new ValueExpr(ValueType.UUID, computationA.toString()),
                                                new ValueExpr(ValueType.UUID, computationB.toString())))))));

        QueryResultPage page = queryRepository.execute(rowQuery(filter, List.of(col("computation_id"), col("value"))));

        assertThat(page.rows()).hasSize(2);
        assertThat(page.rows())
                .extracting(row -> ((Number) row.get("value")).doubleValue())
                .containsExactlyInAnyOrder(0.4, 0.8);
    }

    @Test
    @DisplayName("aggregates value by metric_score_name across a run's computations")
    void aggregatesValueByScoreName() {
        UUID runId = UUID.randomUUID();
        UUID computationA = UUID.randomUUID();
        UUID computationB = UUID.randomUUID();
        metricScoreService.saveAll(List.of(
                result(runId, computationA, "AVG", "Relevancy.score", 0.4),
                result(runId, computationB, "AVG", "Relevancy.score", 0.8)));

        // Explicitly span both computations via `in` (so the rollup is independent of latest-defaulting).
        FilterNode filter = new LogicalNode(
                LogicalOp.AND,
                List.of(
                        eq("test_suite_run_id", runId.toString()),
                        new ComparisonNode(
                                ComparisonOp.IN,
                                List.of(
                                        new FieldExpr("computation_id"),
                                        new ArrayExpr(List.of(
                                                new ValueExpr(ValueType.UUID, computationA.toString()),
                                                new ValueExpr(ValueType.UUID, computationB.toString())))))));
        StructuredQuery query = new StructuredQuery(
                "metric_score_results",
                filter,
                QueryMode.AGGREGATE,
                false,
                List.of(
                        new OutputColumn(new FieldExpr("metric_score_name"), "metric_score_name"),
                        new OutputColumn(new FnExpr("avg", false, List.of(new FieldExpr("value"))), "mean_value")),
                List.of("metric_score_name"),
                null,
                null,
                new OffsetPage(0, 100, false));

        QueryResultPage page = queryRepository.execute(query);

        assertThat(page.rows()).hasSize(1);
        Map<String, Object> row = page.rows().get(0);
        assertThat(row.get("metric_score_name")).isEqualTo("AVG");
        assertThat(((Number) row.get("mean_value")).doubleValue()).isCloseTo(0.6, within(1e-9));
    }

    @Test
    @DisplayName("resolves the computation_id eq \"latest\" sentinel to the run's latest computation")
    void resolvesLatestSentinel() {
        UUID runId = UUID.randomUUID();
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        analyticsTestDataHelper.createRunMetricSnapshot(runId, older, "Relevancy", OUTPUT_SCHEMA, 1_000L);
        analyticsTestDataHelper.createRunMetricSnapshot(runId, newer, "Relevancy", OUTPUT_SCHEMA, 2_000L);
        metricScoreService.saveAll(List.of(
                result(runId, older, "AVG", "Relevancy.score", 0.4),
                result(runId, newer, "AVG", "Relevancy.score", 0.8)));

        // computation_id eq "latest" — the sentinel is resolved before translation (no uuid-parse error).
        FilterNode filter = new LogicalNode(
                LogicalOp.AND,
                List.of(
                        eq("test_suite_run_id", runId.toString()),
                        new ComparisonNode(
                                ComparisonOp.EQ,
                                List.of(new FieldExpr("computation_id"), new ValueExpr(ValueType.STRING, "latest")))));

        QueryResultPage page = queryRepository.execute(rowQuery(filter, List.of(col("computation_id"), col("value"))));

        assertThat(page.rows()).hasSize(1);
        assertThat(page.rows().get(0).get("computation_id")).isEqualTo(newer.toString());
        assertThat(((Number) page.rows().get(0).get("value")).doubleValue()).isEqualTo(0.8);
    }
}
