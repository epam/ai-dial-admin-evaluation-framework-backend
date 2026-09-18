package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.query.model.CaseExpr;
import com.epam.aidial.evaluation.query.model.ComparisonNode;
import com.epam.aidial.evaluation.query.model.ComparisonOp;
import com.epam.aidial.evaluation.query.model.Expr;
import com.epam.aidial.evaluation.query.model.FieldExpr;
import com.epam.aidial.evaluation.query.model.FilterNode;
import com.epam.aidial.evaluation.query.model.FnExpr;
import com.epam.aidial.evaluation.query.model.LogicalNode;
import com.epam.aidial.evaluation.query.model.LogicalOp;
import com.epam.aidial.evaluation.query.model.OutputColumn;
import com.epam.aidial.evaluation.query.model.QueryMode;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.model.ValueExpr;
import com.epam.aidial.evaluation.query.model.ValueType;
import com.epam.aidial.evaluation.query.model.WhenClause;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.util.TracingConstants;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds dial-adas queries against the {@code dial_usage_log} entity: a per-test-case average-cost query
 * scoped to a single run and phase ({@link #buildAvgCostPerTestCaseSql}, raw SQL sent via {@code
 * execute-sql} — the only query here that needs a two-level, sum-then-average aggregation), a
 * {@code "mode": "aggregate"} {@link StructuredQuery} scoped to a deployment and time range
 * ({@link #buildDeploymentAggregateQuery}, filtering on the real {@code deployment}/{@code request_time}
 * columns plus an {@code eval.phase} baggage match), or one scoped to a page of runs at once
 * ({@link #buildPageTotalCostQuery}, one {@code or}-filtered/{@code case}-grouped call for many run ids,
 * no {@code eval.phase} predicate). The two {@link StructuredQuery}-based builders reuse the internal
 * {@link StructuredQuery} model — dial-adas's own query DSL is the same wire contract, so this is the
 * canonical shape, not a lookalike we're guessing at.
 */
@Component
@LogExecution
public class AdasCostQueryBuilder {

    private static final String ENTITY = "dial_usage_log";
    private static final String BAGGAGE_FIELD = "usage_request_baggage.baggage";
    private static final String TOTAL_PRICE_FIELD = "total_price";
    private static final String TOTAL_COST_ALIAS = "total_cost";
    private static final String DEPLOYMENT_FIELD = "deployment";
    private static final String REQUEST_TIME_FIELD = "request_time";
    private static final String RUN_ID_ALIAS = "run_id";
    private static final String OTHER_RUN_ID = "other";

    private static final String AVG_COST_PER_TEST_CASE_SQL_TEMPLATE = """
            WITH tagged AS (
              SELECT total_price, array_join(split_string("usage_request_baggage.baggage", ',')) AS testcase_tag
              FROM dial_usage_log
              WHERE contains("usage_request_baggage.baggage", '%s')
                AND contains("usage_request_baggage.baggage", '%s')
            ),
            per_testcase AS (
              SELECT testcase_tag, sum(total_price) AS case_cost
              FROM tagged
              WHERE starts_with(testcase_tag, '%s')
              GROUP BY testcase_tag
            )
            SELECT avg(case_cost) AS avg_cost, count(*) AS count
            FROM per_testcase""";

    /**
     * Builds the raw SQL query for {@link com.epam.aidial.evaluation.client.dialadas.DialAdasClient#executeSql}
     * that sums each test case's {@code total_price} within the given run and phase, then averages those
     * per-test-case sums — a two-level aggregation dial-adas's structured JSON query DSL cannot express in
     * one call. Output columns are aliased {@code avg_cost}/{@code count} to match
     * {@link com.epam.aidial.evaluation.client.dialadas.dto.AdasRunAvgCostRowDto} exactly, the same row DTO
     * used by the structured-query-based builders in this class. {@code runId} is a {@link UUID} (hex
     * digits and hyphens only) and {@code phase} is restricted to the two known
     * {@link TracingConstants} phase values, so this literal SQL construction carries no injection surface.
     */
    public String buildAvgCostPerTestCaseSql(UUID runId, String phase) {
        if (!TracingConstants.PHASE_EXECUTION.equals(phase)
                && !TracingConstants.PHASE_METRIC_EVALUATION.equals(phase)) {
            throw new IllegalArgumentException("Unsupported phase: " + phase);
        }
        return AVG_COST_PER_TEST_CASE_SQL_TEMPLATE.formatted(
                TracingConstants.EVAL_RUN_ID + "=" + runId,
                TracingConstants.EVAL_PHASE + "=" + phase,
                TracingConstants.TESTCASE_ID + "=");
    }

    public StructuredQuery buildDeploymentAggregateQuery(String deploymentId, long fromMs, long toMs, String phase) {
        Expr baggageValue = baggageField();

        FilterNode filter = new LogicalNode(
                LogicalOp.AND,
                List.of(
                        eq(DEPLOYMENT_FIELD, stringValue(deploymentId)),
                        ge(REQUEST_TIME_FIELD, timestampValue(fromMs)),
                        le(REQUEST_TIME_FIELD, timestampValue(toMs)),
                        baggageContains(baggageValue, TracingConstants.EVAL_PHASE + "=" + phase)));

        return new StructuredQuery(
                ENTITY, filter, QueryMode.AGGREGATE, false, selectCountAndSumCost(), List.of(), null, null, null);
    }

    /**
     * Builds a single aggregate query totaling cost across {@code runIds}, spanning both {@code
     * eval.phase} values (unlike every other query this builder produces). Filters on an {@code or} of
     * per-run baggage-contains predicates (unwrapped to the bare predicate when {@code runIds} has
     * exactly one entry); groups by a {@code run_id} alias re-derived per row via a {@code case}
     * expression that maps each row back to the run id whose baggage predicate matched it, falling back
     * to the {@code "other"} sentinel for rows matched by none (unreachable in practice, since the
     * select's own filter already restricts to matching rows).
     */
    public StructuredQuery buildPageTotalCostQuery(Collection<UUID> runIds) {
        Expr baggageValue = baggageField();

        List<FilterNode> runPredicates = runIds.stream()
                .map(id -> baggageContains(baggageValue, runIdBaggageValue(id)))
                .toList();
        FilterNode filter =
                runPredicates.size() == 1 ? runPredicates.getFirst() : new LogicalNode(LogicalOp.OR, runPredicates);

        List<WhenClause> whenClauses = runIds.stream()
                .map(id -> new WhenClause(
                        baggageContains(baggageValue, runIdBaggageValue(id)), stringValue(id.toString())))
                .toList();
        OutputColumn runIdColumn = new OutputColumn(new CaseExpr(whenClauses, stringValue(OTHER_RUN_ID)), RUN_ID_ALIAS);

        List<OutputColumn> select = List.of(
                runIdColumn,
                new OutputColumn(new FnExpr("count", false, List.of()), null),
                new OutputColumn(
                        new FnExpr("sum", false, List.of(new FieldExpr(TOTAL_PRICE_FIELD))), TOTAL_COST_ALIAS));

        return new StructuredQuery(
                ENTITY, filter, QueryMode.AGGREGATE, false, select, List.of(RUN_ID_ALIAS), null, null, null);
    }

    private static String runIdBaggageValue(UUID runId) {
        return TracingConstants.EVAL_RUN_ID + "=" + runId;
    }

    private static List<OutputColumn> selectCountAndSumCost() {
        return List.of(
                new OutputColumn(new FnExpr("count", false, List.of()), null),
                new OutputColumn(
                        new FnExpr("sum", false, List.of(new FieldExpr(TOTAL_PRICE_FIELD))), TOTAL_COST_ALIAS));
    }

    private static FilterNode baggageContains(Expr baggageValue, String substring) {
        return new ComparisonNode(ComparisonOp.CO, List.of(baggageValue, stringValue(substring)));
    }

    private static Expr baggageField() {
        return new FieldExpr(BAGGAGE_FIELD);
    }

    private static FilterNode eq(String field, Expr value) {
        return new ComparisonNode(ComparisonOp.EQ, List.of(new FieldExpr(field), value));
    }

    private static FilterNode ge(String field, Expr value) {
        return new ComparisonNode(ComparisonOp.GE, List.of(new FieldExpr(field), value));
    }

    private static FilterNode le(String field, Expr value) {
        return new ComparisonNode(ComparisonOp.LE, List.of(new FieldExpr(field), value));
    }

    private static Expr stringValue(String value) {
        return new ValueExpr(ValueType.STRING, value);
    }

    private static Expr timestampValue(long epochMillis) {
        return new ValueExpr(ValueType.TIMESTAMP, String.valueOf(epochMillis));
    }
}
