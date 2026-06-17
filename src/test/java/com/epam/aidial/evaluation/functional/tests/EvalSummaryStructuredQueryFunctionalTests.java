package com.epam.aidial.evaluation.functional.tests;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.data.db.analytics.model.ExecutionStatus;
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
import com.epam.aidial.evaluation.experimental.query.service.repository.EvalSummaryQueryRepository;
import com.epam.aidial.evaluation.experimental.query.service.repository.QueryResultPage;
import com.epam.aidial.evaluation.functional.helper.AnalyticsTestDataHelper;
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
    private EvalSummaryQueryRepository queryRepository;

    @Autowired
    private AnalyticsTestDataHelper analyticsTestDataHelper;

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
                                        new FieldExpr("testCaseName"),
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
    @DisplayName("projects flattened data: and metric: fields from JSONB columns")
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
                List.of(col(new FieldExpr("data:question")), col(new FieldExpr("metric:Exact Match1:exact_match")))));

        assertThat(page.rows()).hasSize(1);
        Map<String, Object> row = page.rows().get(0);
        assertThat(row.get("data:question")).isEqualTo("what is 2+2?");
        assertThat(((Number) row.get("metric:Exact Match1:exact_match")).intValue())
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
                                        new FieldExpr("metric:Exact Match1:exact_match"),
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

        // avg() over metricInfo:<name>, which resolves to a JSONB object — Postgres has no avg(jsonb).
        StructuredQuery query = new StructuredQuery(
                "eval_summaries",
                runIdEq(runId),
                QueryMode.AGGREGATE,
                false,
                List.of(
                        new OutputColumn(new FieldExpr("test_suite_run_id"), null),
                        new OutputColumn(
                                new FnExpr("avg", false, List.of(new FieldExpr("metricInfo:Regex Match1"))), "avg")),
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
        StructuredQuery query = new StructuredQuery(
                "test_suites", null, QueryMode.ROW, false, null, null, null, null, new OffsetPage(0, 10, false));

        assertThatThrownBy(() -> queryRepository.execute(query))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("eval_summaries");
    }
}
