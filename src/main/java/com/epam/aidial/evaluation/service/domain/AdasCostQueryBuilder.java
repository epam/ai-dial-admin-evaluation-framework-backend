package com.epam.aidial.evaluation.service.domain;

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
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.util.TracingConstants;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds dial-adas {@code "mode": "aggregate"} queries against the {@code dial_usage_log} entity for a
 * given execution phase, either scoped to a single run ({@link #buildRunAggregateQuery}, filtering on
 * {@code usage_request_baggage.baggage} containing both {@code eval.run.id=<runId>} and
 * {@code eval.phase=<phase>}) or scoped to a deployment and time range
 * ({@link #buildDeploymentAggregateQuery}, filtering on the real {@code deployment}/{@code request_time}
 * columns plus the same {@code eval.phase} baggage match). Reuses the internal {@link StructuredQuery}
 * model — dial-adas's own query DSL is the same wire contract, so this is the canonical shape, not a
 * lookalike we're guessing at.
 */
@Component
@LogExecution
public class AdasCostQueryBuilder {

    private static final String ENTITY = "dial_usage_log";
    private static final String BAGGAGE_FIELD = "usage_request_baggage.baggage";
    private static final String TOTAL_PRICE_FIELD = "total_price";
    private static final String AVG_COST_ALIAS = "avg_cost";
    private static final String TOTAL_COST_ALIAS = "total_cost";
    private static final String DEPLOYMENT_FIELD = "deployment";
    private static final String REQUEST_TIME_FIELD = "request_time";

    public StructuredQuery buildRunAggregateQuery(UUID runId, String phase) {
        Expr baggageValue = baggageField();

        FilterNode filter = new LogicalNode(
                LogicalOp.AND,
                List.of(
                        baggageContains(baggageValue, TracingConstants.EVAL_RUN_ID + "=" + runId),
                        baggageContains(baggageValue, TracingConstants.EVAL_PHASE + "=" + phase)));

        return new StructuredQuery(
                ENTITY, filter, QueryMode.AGGREGATE, false, selectCountAndAvgCost(), List.of(), null, null, null);
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

    private static List<OutputColumn> selectCountAndAvgCost() {
        return List.of(
                new OutputColumn(new FnExpr("count", false, List.of()), null),
                new OutputColumn(new FnExpr("avg", false, List.of(new FieldExpr(TOTAL_PRICE_FIELD))), AVG_COST_ALIAS));
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
