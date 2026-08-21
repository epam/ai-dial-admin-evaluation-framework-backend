package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.query.model.ComparisonNode;
import com.epam.aidial.evaluation.query.model.ComparisonOp;
import com.epam.aidial.evaluation.query.model.FieldExpr;
import com.epam.aidial.evaluation.query.model.FnExpr;
import com.epam.aidial.evaluation.query.model.LogicalNode;
import com.epam.aidial.evaluation.query.model.LogicalOp;
import com.epam.aidial.evaluation.query.model.OutputColumn;
import com.epam.aidial.evaluation.query.model.QueryMode;
import com.epam.aidial.evaluation.query.model.StructuredQuery;
import com.epam.aidial.evaluation.query.model.ValueExpr;
import com.epam.aidial.evaluation.query.model.ValueType;
import com.epam.aidial.evaluation.runner.util.TracingConstants;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("RunCostQueryBuilder")
class RunCostQueryBuilderTest {

    private static final UUID RUN_ID = UUID.fromString("1f810de3-cb9b-4e50-b9c5-794c41d99f6c");

    private final RunCostQueryBuilder builder = new RunCostQueryBuilder();

    @Test
    @DisplayName("builds the execution-phase aggregate query as a typed StructuredQuery")
    void buildsExecutionPhaseQuery() {
        StructuredQuery query = builder.buildAggregateQuery(RUN_ID, TracingConstants.PHASE_EXECUTION);

        assertThat(query.entity()).isEqualTo("dial_usage_log");
        assertThat(query.mode()).isEqualTo(QueryMode.AGGREGATE);
        assertThat(query.groupBy()).isEmpty();
        assertThat(query.filter())
                .isEqualTo(new LogicalNode(
                        LogicalOp.AND,
                        List.of(
                                baggageContains(TracingConstants.EVAL_RUN_ID + "=" + RUN_ID),
                                baggageContains(
                                        TracingConstants.EVAL_PHASE + "=" + TracingConstants.PHASE_EXECUTION))));
        assertThat(query.select())
                .containsExactly(
                        new OutputColumn(new FnExpr("count", false, List.of()), null),
                        new OutputColumn(new FnExpr("avg", false, List.of(new FieldExpr("total_price"))), "avg_cost"));
    }

    @Test
    @DisplayName("builds the metric-evaluation-phase query with the same shape but a different phase value")
    void buildsMetricEvaluationPhaseQuery() {
        StructuredQuery query = builder.buildAggregateQuery(RUN_ID, TracingConstants.PHASE_METRIC_EVALUATION);

        assertThat(query.filter())
                .isEqualTo(new LogicalNode(
                        LogicalOp.AND,
                        List.of(
                                baggageContains(TracingConstants.EVAL_RUN_ID + "=" + RUN_ID),
                                baggageContains(TracingConstants.EVAL_PHASE + "="
                                        + TracingConstants.PHASE_METRIC_EVALUATION))));
    }

    @Test
    @DisplayName("serializes to the exact JSON dial-adas expects on the wire")
    void serializesToDialAdasWireShape() {
        // Mirrors the production JsonMapper bean's default inclusion (JsonMapperConfiguration.createJsonMapper).
        JsonMapper objectMapper = JsonMapper.builder()
                .changeDefaultPropertyInclusion(
                        v -> JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
                .build();

        StructuredQuery query = builder.buildAggregateQuery(RUN_ID, TracingConstants.PHASE_EXECUTION);
        JsonNode actual = objectMapper.valueToTree(query);

        JsonNode expected = objectMapper.readTree("""
                {
                  "entity": "dial_usage_log",
                  "mode": "aggregate",
                  "distinct": false,
                  "filter": {
                    "op": "and",
                    "args": [
                      {
                        "op": "co",
                        "args": [
                          { "type": "fn", "name": "json_extract_string", "distinct": false, "args": [
                              { "type": "field", "name": "request_tags" },
                              { "type": "value", "value_type": "string", "value": "baggage" }
                          ] },
                          { "type": "value", "value_type": "string", "value": "eval.run.id=1f810de3-cb9b-4e50-b9c5-794c41d99f6c" }
                        ]
                      },
                      {
                        "op": "co",
                        "args": [
                          { "type": "fn", "name": "json_extract_string", "distinct": false, "args": [
                              { "type": "field", "name": "request_tags" },
                              { "type": "value", "value_type": "string", "value": "baggage" }
                          ] },
                          { "type": "value", "value_type": "string", "value": "eval.phase=execution" }
                        ]
                      }
                    ]
                  },
                  "group_by": [],
                  "select": [
                    { "expr": { "type": "fn", "name": "count", "distinct": false, "args": [] } },
                    { "expr": { "type": "fn", "name": "avg", "distinct": false, "args": [ { "type": "field", "name": "total_price" } ] }, "as": "avg_cost" }
                  ]
                }
                """);

        assertThat(actual).isEqualTo(expected);
    }

    private static ComparisonNode baggageContains(String substring) {
        return new ComparisonNode(
                ComparisonOp.CO,
                List.of(
                        new FnExpr(
                                "json_extract_string",
                                false,
                                List.of(new FieldExpr("request_tags"), new ValueExpr(ValueType.STRING, "baggage"))),
                        new ValueExpr(ValueType.STRING, substring)));
    }
}
