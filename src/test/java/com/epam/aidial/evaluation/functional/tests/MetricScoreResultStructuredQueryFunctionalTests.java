package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
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
import com.epam.aidial.evaluation.experimental.query.model.SortDir;
import com.epam.aidial.evaluation.experimental.query.model.SortItem;
import com.epam.aidial.evaluation.experimental.query.model.StructuredQuery;
import com.epam.aidial.evaluation.experimental.query.model.SubqueryExpr;
import com.epam.aidial.evaluation.experimental.query.model.ValueExpr;
import com.epam.aidial.evaluation.experimental.query.model.ValueType;
import com.epam.aidial.evaluation.experimental.query.service.MetricScoreResultSchemaProvider;
import com.epam.aidial.evaluation.experimental.query.service.StructuredQueryService;
import com.epam.aidial.evaluation.experimental.query.service.dto.QueryFieldType;
import com.epam.aidial.evaluation.experimental.query.service.dto.QuerySchemaFieldDto;
import com.epam.aidial.evaluation.experimental.query.service.repository.QueryResultPage;
import com.epam.aidial.evaluation.experimental.query.service.repository.StructuredQueryExecutor;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.service.domain.analytics.MetricScoreService;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
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

    /** Default suite/timestamp for cases that don't exercise the suite-scoped, time-ordered path. */
    private static final UUID DEFAULT_SUITE_ID = UUID.fromString("99999999-9999-9999-9999-999999999999");

    private static final long DEFAULT_COMPUTED_AT_MS = 1_000L;

    @Autowired
    private StructuredQueryExecutor queryRepository;

    @Autowired
    private MetricScoreService metricScoreService;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private MetricScoreResultSchemaProvider schemaProvider;

    @Autowired
    private StructuredQueryService structuredQueryService;

    private static MetricScoreResult result(
            UUID runId, UUID computationId, String scoreName, String metricName, double value) {
        return result(runId, DEFAULT_SUITE_ID, computationId, scoreName, metricName, value, DEFAULT_COMPUTED_AT_MS);
    }

    private static MetricScoreResult result(
            UUID runId,
            UUID suiteId,
            UUID computationId,
            String scoreName,
            String metricName,
            double value,
            long computedAtMs) {
        return MetricScoreResult.builder()
                .id(UUID.randomUUID())
                .testSuiteRunId(runId)
                .testSuiteId(suiteId)
                .computationId(computationId)
                .metricScoreName(scoreName)
                .metricName(metricName)
                .value(value)
                .computedAtMs(computedAtMs)
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

    @Test
    @DisplayName("returns the latest N results for a suite ordered by computed_at_ms descending")
    void latestResultsForSuiteOrderedByComputedAt() {
        UUID suiteId = UUID.randomUUID();
        // Three runs of the same suite, each computed at a distinct time.
        metricScoreService.saveAll(List.of(
                result(UUID.randomUUID(), suiteId, UUID.randomUUID(), "overall", "overall", 0.1, 1_000L),
                result(UUID.randomUUID(), suiteId, UUID.randomUUID(), "overall", "overall", 0.2, 2_000L),
                result(UUID.randomUUID(), suiteId, UUID.randomUUID(), "overall", "overall", 0.3, 3_000L)));

        StructuredQuery query = new StructuredQuery(
                "metric_score_results",
                eq("test_suite_id", suiteId.toString()),
                QueryMode.ROW,
                false,
                List.of(col("computed_at_ms"), col("value")),
                null,
                null,
                List.of(new SortItem("computed_at_ms", SortDir.DESC, null)),
                new OffsetPage(0, 2, false));

        QueryResultPage page = queryRepository.execute(query);

        // Latest 2 of the 3 runs, in descending compute-time order.
        assertThat(page.rows()).hasSize(2);
        assertThat(page.rows())
                .extracting(row -> ((Number) row.get("computed_at_ms")).longValue())
                .containsExactly(3_000L, 2_000L);
    }

    @Test
    @DisplayName("exposes test_suite_id and computed_at_ms as queryable fields on the entity schema")
    void exposesSuiteAndTimestampInSchema() {
        List<QuerySchemaFieldDto> schema = schemaProvider.baseSchema();

        assertThat(schema)
                .extracting(QuerySchemaFieldDto::name, QuerySchemaFieldDto::type)
                .contains(tuple("test_suite_id", QueryFieldType.UUID), tuple("computed_at_ms", QueryFieldType.LONG));
    }

    /** An aggregate subquery: the latest {@code limit} runs of {@code suiteId} (run id first, recency second). */
    private static StructuredQuery latestRunsSubquery(UUID suiteId, int limit) {
        return new StructuredQuery(
                "metric_score_results",
                eq("test_suite_id", suiteId.toString()),
                QueryMode.AGGREGATE,
                false,
                List.of(
                        new OutputColumn(new FieldExpr("test_suite_run_id"), "test_suite_run_id"),
                        new OutputColumn(
                                new FnExpr("max", false, List.of(new FieldExpr("computed_at_ms"))), "recency")),
                List.of("test_suite_run_id"),
                null,
                List.of(new SortItem("recency", SortDir.DESC, null)),
                new OffsetPage(0, limit, false));
    }

    @Test
    @DisplayName("returns all metric scores for the latest N runs of a suite in a single request")
    void returnsAllScoresForLatestRunsInSingleRequest() {
        UUID suiteId = UUID.randomUUID();
        UUID run1 = UUID.randomUUID();
        UUID run2 = UUID.randomUUID();
        UUID run3 = UUID.randomUUID();
        UUID c1 = UUID.randomUUID();
        UUID c2 = UUID.randomUUID();
        UUID c3 = UUID.randomUUID();
        // Each run: one computation, 3 score rows sharing the run's compute time (run3 newest).
        metricScoreService.saveAll(List.of(
                result(run1, suiteId, c1, "AVG", "Relevancy.score", 0.1, 1_000L),
                result(run1, suiteId, c1, "P90", "Relevancy.score", 0.2, 1_000L),
                result(run1, suiteId, c1, "overall", "overall", 0.15, 1_000L),
                result(run2, suiteId, c2, "AVG", "Relevancy.score", 0.3, 2_000L),
                result(run2, suiteId, c2, "P90", "Relevancy.score", 0.4, 2_000L),
                result(run2, suiteId, c2, "overall", "overall", 0.35, 2_000L),
                result(run3, suiteId, c3, "AVG", "Relevancy.score", 0.5, 3_000L),
                result(run3, suiteId, c3, "P90", "Relevancy.score", 0.6, 3_000L),
                result(run3, suiteId, c3, "overall", "overall", 0.55, 3_000L)));

        // Single request: all score rows whose run is among the latest 2 runs of the suite.
        FilterNode filter = new ComparisonNode(
                ComparisonOp.IN,
                List.of(new FieldExpr("test_suite_run_id"), new SubqueryExpr(latestRunsSubquery(suiteId, 2))));
        StructuredQuery query = new StructuredQuery(
                "metric_score_results",
                filter,
                QueryMode.ROW,
                false,
                List.of(col("test_suite_run_id"), col("metric_score_name"), col("value"), col("computed_at_ms")),
                null,
                null,
                List.of(new SortItem("computed_at_ms", SortDir.DESC, null)),
                new OffsetPage(0, 1000, false));

        // One request: the subquery is compiled to a nested SELECT during translation.
        QueryResultPage page = structuredQueryService.execute(query);

        // All 3 rows of run3 + all 3 rows of run2; run1 (oldest) excluded.
        assertThat(page.rows()).hasSize(6);
        assertThat(page.rows())
                .extracting(row -> row.get("test_suite_run_id"))
                .containsOnly(run2.toString(), run3.toString());
    }

    @Test
    @DisplayName("returns no rows when the 'in' subquery matches nothing")
    void emptySubqueryReturnsNoRows() {
        // A suite with no scored runs: the subquery yields no run ids -> empty membership set -> no rows.
        StructuredQuery query = new StructuredQuery(
                "metric_score_results",
                new ComparisonNode(
                        ComparisonOp.IN,
                        List.of(
                                new FieldExpr("test_suite_run_id"),
                                new SubqueryExpr(latestRunsSubquery(UUID.randomUUID(), 2)))),
                QueryMode.ROW,
                false,
                List.of(col("value")),
                null,
                null,
                null,
                new OffsetPage(0, 100, false));

        assertThat(structuredQueryService.execute(query).rows()).isEmpty();
    }

    /** An aggregate subquery: this suite's own max {@code computed_at_ms} (a single scalar value). */
    private static StructuredQuery latestComputedAtSubquery(UUID suiteId) {
        return new StructuredQuery(
                "metric_score_results",
                eq("test_suite_id", suiteId.toString()),
                QueryMode.AGGREGATE,
                false,
                List.of(new OutputColumn(new FnExpr("max", false, List.of(new FieldExpr("computed_at_ms"))), "latest")),
                List.of(),
                null,
                null,
                new OffsetPage(0, 1, false));
    }

    @Test
    @DisplayName("filters using a subquery as a scalar comparison operand, not just 'in'")
    void filtersUsingScalarSubqueryComparison() {
        UUID suiteId = UUID.randomUUID();
        UUID run1 = UUID.randomUUID();
        UUID run2 = UUID.randomUUID();
        UUID c1 = UUID.randomUUID();
        UUID c2 = UUID.randomUUID();
        metricScoreService.saveAll(List.of(
                result(run1, suiteId, c1, "AVG", "Relevancy.score", 0.1, 1_000L),
                result(run1, suiteId, c1, "overall", "overall", 0.15, 1_000L),
                result(run2, suiteId, c2, "AVG", "Relevancy.score", 0.5, 2_000L),
                result(run2, suiteId, c2, "overall", "overall", 0.55, 2_000L)));

        // Only rows computed at this suite's own latest compute time (2000, run2's) should match.
        // Also scoped to this suite explicitly: metric_score_result is shared across every test method
        // in this class, so an unscoped computed_at_ms match alone could pick up unrelated rows from
        // other tests that happen to share the same literal timestamp.
        FilterNode filter = new LogicalNode(
                LogicalOp.AND,
                List.of(
                        eq("test_suite_id", suiteId.toString()),
                        new ComparisonNode(
                                ComparisonOp.EQ,
                                List.of(
                                        new FieldExpr("computed_at_ms"),
                                        new SubqueryExpr(latestComputedAtSubquery(suiteId))))));
        StructuredQuery query = new StructuredQuery(
                "metric_score_results",
                filter,
                QueryMode.ROW,
                false,
                List.of(col("test_suite_run_id"), col("metric_score_name"), col("computed_at_ms")),
                null,
                null,
                null,
                new OffsetPage(0, 100, false));

        QueryResultPage page = structuredQueryService.execute(query);

        assertThat(page.rows()).hasSize(2);
        assertThat(page.rows()).extracting(row -> row.get("test_suite_run_id")).containsOnly(run2.toString());
    }

    @Test
    @DisplayName("projects a subquery as a select column")
    void projectsSubqueryAsSelectColumn() {
        UUID suiteId = UUID.randomUUID();
        UUID run1 = UUID.randomUUID();
        UUID c1 = UUID.randomUUID();
        metricScoreService.saveAll(List.of(result(run1, suiteId, c1, "AVG", "Relevancy.score", 0.1, 1_000L)));

        StructuredQuery query = new StructuredQuery(
                "metric_score_results",
                eq("test_suite_run_id", run1.toString()),
                QueryMode.ROW,
                false,
                List.of(
                        col("metric_score_name"),
                        new OutputColumn(new SubqueryExpr(latestComputedAtSubquery(suiteId)), "suite_latest")),
                null,
                null,
                null,
                new OffsetPage(0, 100, false));

        QueryResultPage page = structuredQueryService.execute(query);

        assertThat(page.rows()).isNotEmpty();
        assertThat(page.rows())
                .allSatisfy(row -> assertThat(((Number) row.get("suite_latest")).longValue())
                        .isEqualTo(1_000L));
    }

    @Test
    @DisplayName("an 'in' subquery targeting a different, cross-datasource entity fails at the database")
    void rejectsCrossDatasourceSubquery() {
        // test_suites lives on the meta datasource; metric_score_results on analytics — nesting one
        // inside the other is not a structural rule anymore (same-entity check was removed), but it
        // still can't succeed: Postgres rejects the nested SQL referencing a table from a different
        // connection/schema, surfaced as a normal grammar error and mapped to 400 like any other
        // database-level type/grammar mismatch.
        StructuredQuery inner = new StructuredQuery(
                "test_suites",
                null,
                QueryMode.ROW,
                false,
                List.of(col("id")),
                null,
                null,
                null,
                new OffsetPage(0, 2, false));
        StructuredQuery query = new StructuredQuery(
                "metric_score_results",
                new ComparisonNode(
                        ComparisonOp.IN, List.of(new FieldExpr("test_suite_run_id"), new SubqueryExpr(inner))),
                QueryMode.ROW,
                false,
                List.of(col("value")),
                null,
                null,
                null,
                new OffsetPage(0, 100, false));

        assertThatThrownBy(() -> structuredQueryService.execute(query)).isInstanceOf(ValidationException.class);
    }
}
