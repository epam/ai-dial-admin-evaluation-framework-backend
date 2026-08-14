package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.within;

import com.epam.aidial.evaluation.data.db.analytics.model.EvalSummary;
import com.epam.aidial.evaluation.data.db.analytics.repository.EvalSummaryRepository;
import com.epam.aidial.evaluation.experimental.query.model.ArrayExpr;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonNode;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonOp;
import com.epam.aidial.evaluation.experimental.query.model.Expr;
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
import com.epam.aidial.evaluation.experimental.query.service.repository.QueryResultPage;
import com.epam.aidial.evaluation.experimental.query.service.repository.StructuredQueryExecutor;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
import com.epam.aidial.evaluation.functional.helper.EvalSummaryFixture;
import com.epam.aidial.evaluation.runner.model.ExecutionStatus;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

@DisplayName("Structured Query → jOOQ translation (eval_summaries) Tests")
public abstract class EvalSummaryStructuredQueryFunctionalTests extends BaseFunctionalTest {

    @Autowired
    private StructuredQueryExecutor queryRepository;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

    @Autowired
    private EvalSummaryRepository evalSummaryRepository;

    private static StructuredQuery rowQuery(FilterNode filter, List<OutputColumn> select) {
        return new StructuredQuery(
                "eval_summaries",
                filter,
                QueryMode.ROW,
                false,
                select,
                null,
                null,
                null,
                new OffsetPage(0, 100, false));
    }

    private static OutputColumn col(Expr expr) {
        return new OutputColumn(expr, null);
    }

    private static ComparisonNode eq(String field, ValueType type, String value) {
        return new ComparisonNode(ComparisonOp.EQ, List.of(new FieldExpr(field), new ValueExpr(type, value)));
    }

    private static ComparisonNode runIdEq(UUID runId) {
        return eq("test_suite_run_id", ValueType.UUID, runId.toString());
    }

    @Test
    @DisplayName("filters eval_summaries by execution status within a run")
    void filtersByExecutionStatus() {
        UUID suiteId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runId, computationId, "case-a", ExecutionStatus.SUCCESS.name(), 100L, 1_000L);
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runId, computationId, "case-b", ExecutionStatus.SUCCESS.name(), 200L, 2_000L);
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runId, computationId, "case-c", ExecutionStatus.FAILED.name(), 300L, 3_000L);

        FilterNode filter = new LogicalNode(
                LogicalOp.AND,
                List.of(runIdEq(runId), eq("execution_status", ValueType.STRING, ExecutionStatus.SUCCESS.name())));

        QueryResultPage page = queryRepository.execute(rowQuery(
                filter, List.of(col(new FieldExpr("test_case_name")), col(new FieldExpr("execution_status")))));

        assertThat(page.rows())
                .extracting(row -> row.get("test_case_name"))
                .containsExactlyInAnyOrder("case-a", "case-b");
        assertThat(page.rows())
                .allSatisfy(row -> assertThat(row.get("execution_status")).isEqualTo(ExecutionStatus.SUCCESS.name()));
    }

    @Test
    @DisplayName("translates 'in' over test case names into an IN list within a run")
    void filtersByNameInList() {
        UUID suiteId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runId, computationId, "case-a", ExecutionStatus.SUCCESS.name(), 100L, 1_000L);
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runId, computationId, "case-b", ExecutionStatus.SUCCESS.name(), 200L, 2_000L);
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runId, computationId, "case-c", ExecutionStatus.SUCCESS.name(), 300L, 3_000L);

        FilterNode filter = new LogicalNode(
                LogicalOp.AND,
                List.of(
                        runIdEq(runId),
                        new ComparisonNode(
                                ComparisonOp.IN,
                                List.of(
                                        new FieldExpr("test_case_name"),
                                        new ArrayExpr(List.of(
                                                new ValueExpr(ValueType.STRING, "case-a"),
                                                new ValueExpr(ValueType.STRING, "case-c")))))));

        QueryResultPage page = queryRepository.execute(rowQuery(filter, List.of(col(new FieldExpr("test_case_name")))));

        assertThat(page.rows())
                .extracting(row -> row.get("test_case_name"))
                .containsExactlyInAnyOrder("case-a", "case-c");
    }

    @Test
    @DisplayName("executes count/min/max/avg aggregates over a run's execution durations in one row")
    void aggregatesNumericFunctions() {
        UUID suiteId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runId, computationId, "case-a", ExecutionStatus.SUCCESS.name(), 100L, 1_000L);
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runId, computationId, "case-b", ExecutionStatus.SUCCESS.name(), 200L, 2_000L);
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runId, computationId, "case-c", ExecutionStatus.SUCCESS.name(), 300L, 3_000L);

        StructuredQuery query = new StructuredQuery(
                "eval_summaries",
                runIdEq(runId),
                QueryMode.AGGREGATE,
                false,
                List.of(
                        new OutputColumn(new FnExpr("count", false, List.of()), "total"),
                        new OutputColumn(
                                new FnExpr("min", false, List.of(new FieldExpr("exec_duration_ms"))), "fastest"),
                        new OutputColumn(
                                new FnExpr("max", false, List.of(new FieldExpr("exec_duration_ms"))), "slowest"),
                        new OutputColumn(new FnExpr("avg", false, List.of(new FieldExpr("exec_duration_ms"))), "mean")),
                null,
                null,
                null,
                new OffsetPage(0, 100, false));

        QueryResultPage page = queryRepository.execute(query);

        assertThat(page.rows()).hasSize(1);
        Map<String, Object> row = page.rows().get(0);
        assertThat(((Number) row.get("total")).intValue()).isEqualTo(3);
        assertThat(((Number) row.get("fastest")).longValue()).isEqualTo(100L);
        assertThat(((Number) row.get("slowest")).longValue()).isEqualTo(300L);
        assertThat(((Number) row.get("mean")).doubleValue()).isEqualTo(200.0);
    }

    @Test
    @DisplayName("persists avgMetricEvalDurationMs and averages it via the DSL without any resolver changes")
    void aggregatesAvgMetricEvalDurationMs() {
        UUID suiteId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();

        UUID idA = analyticsTestDataHelper.createEvalSummary(EvalSummaryFixture.builder()
                .suiteId(suiteId)
                .runId(runId)
                .computationId(computationId)
                .testCaseName("case-a")
                .createdAtMs(1_000L)
                .avgMetricEvalDurationMs(100L)
                .build());
        analyticsTestDataHelper.createEvalSummary(EvalSummaryFixture.builder()
                .suiteId(suiteId)
                .runId(runId)
                .computationId(computationId)
                .testCaseName("case-b")
                .createdAtMs(2_000L)
                .avgMetricEvalDurationMs(300L)
                .build());

        EvalSummary persisted = evalSummaryRepository.findById(idA).orElseThrow();
        assertThat(persisted.getAvgMetricEvalDurationMs()).isEqualTo(100L);

        StructuredQuery query = new StructuredQuery(
                "eval_summaries",
                runIdEq(runId),
                QueryMode.AGGREGATE,
                false,
                List.of(new OutputColumn(
                        new FnExpr("avg", false, List.of(new FieldExpr("avg_metric_eval_duration_ms"))), "mean")),
                null,
                null,
                null,
                new OffsetPage(0, 100, false));

        QueryResultPage page = queryRepository.execute(query);

        assertThat(page.rows()).hasSize(1);
        assertThat(((Number) page.rows().get(0).get("mean")).doubleValue()).isEqualTo(200.0);
    }

    @Test
    @DisplayName("populates totalCount when offset paging requests include_total")
    void populatesTotalCount() {
        UUID suiteId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runId, computationId, "case-a", ExecutionStatus.SUCCESS.name(), 100L, 1_000L);
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runId, computationId, "case-b", ExecutionStatus.SUCCESS.name(), 200L, 2_000L);

        StructuredQuery query = new StructuredQuery(
                "eval_summaries",
                runIdEq(runId),
                QueryMode.ROW,
                false,
                null,
                null,
                null,
                null,
                new OffsetPage(0, 1, true));

        QueryResultPage page = queryRepository.execute(query);

        assertThat(page.rows()).hasSize(1); // limited to 1
        assertThat(page.totalCount()).isEqualTo(2L); // but total reflects all matches in the run
    }

    @Test
    @DisplayName("projects flattened data:: and metric:: fields from JSONB columns")
    void projectsFlattenedJsonbFields() {
        UUID suiteId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();
        analyticsTestDataHelper.createEvalSummary(
                suiteId,
                runId,
                computationId,
                "case-a",
                ExecutionStatus.SUCCESS.name(),
                100L,
                1_000L,
                "{\"question\":\"what is 2+2?\"}",
                "{\"Exact Match1\":{\"exact_match\":1}}");

        QueryResultPage page = queryRepository.execute(rowQuery(
                runIdEq(runId),
                List.of(
                        col(new FieldExpr("data::question")),
                        col(new FieldExpr("metric::Exact Match1::exact_match")))));

        assertThat(page.rows()).hasSize(1);
        Map<String, Object> row = page.rows().get(0);
        assertThat(row.get("data::question")).isEqualTo("what is 2+2?");
        assertThat(((Number) row.get("metric::Exact Match1::exact_match")).intValue())
                .isEqualTo(1);
    }

    @Test
    @DisplayName("filters numerically on a flattened metric: field")
    void filtersOnFlattenedMetricField() {
        UUID suiteId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();
        analyticsTestDataHelper.createEvalSummary(
                suiteId,
                runId,
                computationId,
                "hit-1",
                ExecutionStatus.SUCCESS.name(),
                100L,
                1_000L,
                "{}",
                "{\"Exact Match1\":{\"exact_match\":1}}");
        analyticsTestDataHelper.createEvalSummary(
                suiteId,
                runId,
                computationId,
                "miss",
                ExecutionStatus.SUCCESS.name(),
                100L,
                2_000L,
                "{}",
                "{\"Exact Match1\":{\"exact_match\":0}}");
        analyticsTestDataHelper.createEvalSummary(
                suiteId,
                runId,
                computationId,
                "hit-2",
                ExecutionStatus.SUCCESS.name(),
                100L,
                3_000L,
                "{}",
                "{\"Exact Match1\":{\"exact_match\":1}}");

        FilterNode filter = new LogicalNode(
                LogicalOp.AND,
                List.of(
                        runIdEq(runId),
                        new ComparisonNode(
                                ComparisonOp.GE,
                                List.of(
                                        new FieldExpr("metric::Exact Match1::exact_match"),
                                        new ValueExpr(ValueType.DECIMAL, "1")))));

        QueryResultPage page = queryRepository.execute(rowQuery(filter, List.of(col(new FieldExpr("test_case_name")))));

        assertThat(page.rows())
                .extracting(row -> row.get("test_case_name"))
                .containsExactlyInAnyOrder("hit-1", "hit-2");
    }

    @Test
    @DisplayName("rejects aggregating a non-numeric JSONB field as a 400, not a 500")
    void rejectsAggregateOnNonNumericJsonbField() {
        UUID suiteId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();
        analyticsTestDataHelper.createEvalSummary(
                suiteId, runId, computationId, "case-a", ExecutionStatus.SUCCESS.name(), 100L, 1_000L);

        // avg() over metricInfo::<name>, which resolves to a JSONB object — Postgres has no avg(jsonb).
        StructuredQuery query = new StructuredQuery(
                "eval_summaries",
                runIdEq(runId),
                QueryMode.AGGREGATE,
                false,
                List.of(
                        new OutputColumn(new FieldExpr("test_suite_run_id"), null),
                        new OutputColumn(
                                new FnExpr("avg", false, List.of(new FieldExpr("metricInfo::Regex Match1"))), "avg")),
                List.of("test_suite_run_id"),
                null,
                null,
                new OffsetPage(0, 25, true));

        assertThatThrownBy(() -> queryRepository.execute(query))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("could not be executed");
    }

    @Test
    @DisplayName("rejects a query targeting an unsupported entity")
    void rejectsUnsupportedEntity() {
        // "test_suites" is itself a valid, registered entity (just not this test's focus), so the
        // shared, entity-agnostic executor would route it successfully rather than reject it; use an
        // entity name that has no registered resolver anywhere to exercise the true rejection path.
        StructuredQuery query = new StructuredQuery(
                "not_a_real_entity", null, QueryMode.ROW, false, null, null, null, null, new OffsetPage(0, 10, false));

        assertThatThrownBy(() -> queryRepository.execute(query))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("not_a_real_entity");
    }

    @Test
    @DisplayName("computes p10/p90 percentile_cont over a run's metric scores in a single row")
    void computesPercentilesOverMetricScores() {
        UUID suiteId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();
        analyticsTestDataHelper.createEvalSummary(
                suiteId,
                runId,
                computationId,
                "case-a",
                ExecutionStatus.SUCCESS.name(),
                100L,
                1_000L,
                "{}",
                "{\"Relevancy\":{\"score\":0.0}}");
        analyticsTestDataHelper.createEvalSummary(
                suiteId,
                runId,
                computationId,
                "case-b",
                ExecutionStatus.SUCCESS.name(),
                200L,
                2_000L,
                "{}",
                "{\"Relevancy\":{\"score\":0.5}}");
        analyticsTestDataHelper.createEvalSummary(
                suiteId,
                runId,
                computationId,
                "case-c",
                ExecutionStatus.SUCCESS.name(),
                300L,
                3_000L,
                "{}",
                "{\"Relevancy\":{\"score\":1.0}}");

        // Aggregate over the whole run (no group_by) → single row with two ordered-set aggregates.
        StructuredQuery query = new StructuredQuery(
                "eval_summaries",
                runIdEq(runId),
                QueryMode.AGGREGATE,
                false,
                List.of(
                        new OutputColumn(percentileCont("0.1", "metric::Relevancy::score"), "p10"),
                        new OutputColumn(percentileCont("0.9", "metric::Relevancy::score"), "p90")),
                null,
                null,
                null,
                new OffsetPage(0, 100, false));

        QueryResultPage page = queryRepository.execute(query);

        assertThat(page.rows()).hasSize(1);
        Map<String, Object> row = page.rows().get(0);
        // percentile_cont over sorted [0.0, 0.5, 1.0]: cont(0.1)=0.1, cont(0.9)=0.9 (linear interpolation).
        assertThat(((Number) row.get("p10")).doubleValue()).isCloseTo(0.1, within(1e-9));
        assertThat(((Number) row.get("p90")).doubleValue()).isCloseTo(0.9, within(1e-9));
    }

    @Test
    @DisplayName("computes roc_auc over a classifier's label/probability outputs across all matching test cases")
    void computesRocAucOverLabelAndProbability() {
        UUID suiteId = UUID.randomUUID();
        UUID runId = UUID.randomUUID();
        UUID computationId = UUID.randomUUID();
        // label/probability pairs: (0, 0.1), (0, 0.4), (1, 0.35), (1, 0.8) -> one discordant pair -> AUC = 0.75.
        analyticsTestDataHelper.createEvalSummary(
                suiteId,
                runId,
                computationId,
                "case-a",
                ExecutionStatus.SUCCESS.name(),
                100L,
                1_000L,
                "{}",
                "{\"Classifier\":{\"label\":0,\"probability\":0.1}}");
        analyticsTestDataHelper.createEvalSummary(
                suiteId,
                runId,
                computationId,
                "case-b",
                ExecutionStatus.SUCCESS.name(),
                200L,
                2_000L,
                "{}",
                "{\"Classifier\":{\"label\":0,\"probability\":0.4}}");
        analyticsTestDataHelper.createEvalSummary(
                suiteId,
                runId,
                computationId,
                "case-c",
                ExecutionStatus.SUCCESS.name(),
                300L,
                3_000L,
                "{}",
                "{\"Classifier\":{\"label\":1,\"probability\":0.35}}");
        analyticsTestDataHelper.createEvalSummary(
                suiteId,
                runId,
                computationId,
                "case-d",
                ExecutionStatus.SUCCESS.name(),
                400L,
                4_000L,
                "{}",
                "{\"Classifier\":{\"label\":1,\"probability\":0.8}}");

        StructuredQuery query = new StructuredQuery(
                "eval_summaries",
                runIdEq(runId),
                QueryMode.AGGREGATE,
                false,
                List.of(new OutputColumn(
                        new FnExpr(
                                "roc_auc",
                                false,
                                List.of(
                                        new FieldExpr("metric::Classifier::label"),
                                        new FieldExpr("metric::Classifier::probability"))),
                        "value")),
                null,
                null,
                null,
                new OffsetPage(0, 100, false));

        QueryResultPage page = queryRepository.execute(query);

        assertThat(page.rows()).hasSize(1);
        assertThat(((Number) page.rows().get(0).get("value")).doubleValue()).isCloseTo(0.75, within(1e-9));
    }

    @Test
    @DisplayName("rejects a percentile fraction outside [0, 1] with a validation error")
    void rejectsPercentileFractionOutOfRange() {
        StructuredQuery query = new StructuredQuery(
                "eval_summaries",
                null,
                QueryMode.AGGREGATE,
                false,
                List.of(new OutputColumn(percentileCont("1.5", "exec_duration_ms"), "bad")),
                null,
                null,
                null,
                new OffsetPage(0, 100, false));

        assertThatThrownBy(() -> queryRepository.execute(query))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("[0, 1]");
    }

    private static FnExpr percentileCont(String fraction, String column) {
        return new FnExpr(
                "percentile_cont", false, List.of(new ValueExpr(ValueType.DECIMAL, fraction), new FieldExpr(column)));
    }
}
