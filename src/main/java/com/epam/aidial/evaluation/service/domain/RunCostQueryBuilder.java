package com.epam.aidial.evaluation.service.domain;

import com.epam.aidial.evaluation.experimental.query.model.ComparisonNode;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonOp;
import com.epam.aidial.evaluation.experimental.query.model.Expr;
import com.epam.aidial.evaluation.experimental.query.model.FieldExpr;
import com.epam.aidial.evaluation.experimental.query.model.FilterNode;
import com.epam.aidial.evaluation.experimental.query.model.FnExpr;
import com.epam.aidial.evaluation.experimental.query.model.LogicalNode;
import com.epam.aidial.evaluation.experimental.query.model.LogicalOp;
import com.epam.aidial.evaluation.experimental.query.model.OutputColumn;
import com.epam.aidial.evaluation.experimental.query.model.QueryMode;
import com.epam.aidial.evaluation.experimental.query.model.StructuredQuery;
import com.epam.aidial.evaluation.experimental.query.model.ValueExpr;
import com.epam.aidial.evaluation.experimental.query.model.ValueType;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.runner.util.TracingConstants;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Builds dial-adas {@code "mode": "aggregate"} queries scoped to a run's usage-log rows for a given
 * execution phase, filtering on {@code request_tags.baggage} containing both {@code eval.run.id=<runId>}
 * and {@code eval.phase=<phase>}. Reuses the internal {@link StructuredQuery} model — dial-adas's own
 * query DSL is the same wire contract, so this is the canonical shape, not a lookalike we're guessing at.
 */
@Component
@LogExecution
public class RunCostQueryBuilder {

    private static final String ENTITY = "dial_usage_log";
    private static final String REQUEST_TAGS_FIELD = "request_tags";
    private static final String BAGGAGE_KEY = "baggage";
    private static final String TOTAL_PRICE_FIELD = "total_price";
    private static final String AVG_COST_ALIAS = "avg_cost";

    public StructuredQuery buildAggregateQuery(UUID runId, String phase) {
        Expr baggageValue = jsonExtractBaggage();

        FilterNode filter = new LogicalNode(
                LogicalOp.AND,
                List.of(
                        baggageContains(baggageValue, TracingConstants.EVAL_RUN_ID + "=" + runId),
                        baggageContains(baggageValue, TracingConstants.EVAL_PHASE + "=" + phase)));

        return new StructuredQuery(
                ENTITY,
                filter,
                QueryMode.AGGREGATE,
                false,
                List.of(
                        new OutputColumn(new FnExpr("count", false, List.of()), null),
                        new OutputColumn(
                                new FnExpr("avg", false, List.of(new FieldExpr(TOTAL_PRICE_FIELD))), AVG_COST_ALIAS)),
                List.of(),
                null,
                null,
                null);
    }

    private static FilterNode baggageContains(Expr baggageValue, String substring) {
        return new ComparisonNode(ComparisonOp.CO, List.of(baggageValue, stringValue(substring)));
    }

    private static Expr jsonExtractBaggage() {
        return new FnExpr(
                "json_extract_string", false, List.of(new FieldExpr(REQUEST_TAGS_FIELD), stringValue(BAGGAGE_KEY)));
    }

    private static Expr stringValue(String value) {
        return new ValueExpr(ValueType.STRING, value);
    }
}
