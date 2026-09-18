package com.epam.aidial.evaluation.service.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.query.model.CaseExpr;
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
import com.epam.aidial.evaluation.query.model.WhenClause;
import com.epam.aidial.evaluation.runner.util.TracingConstants;
import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DisplayName("AdasCostQueryBuilder")
class AdasCostQueryBuilderTest {

    private static final UUID RUN_ID = UUID.fromString("1f810de3-cb9b-4e50-b9c5-794c41d99f6c");
    private static final String DEPLOYMENT_ID = "gpt-4o-2024-08-06";
    private static final long FROM_MS = 1735689600000L;
    private static final long TO_MS = 1738368000000L;

    private final AdasCostQueryBuilder builder = new AdasCostQueryBuilder();

    @Test
    @DisplayName("builds the execution-phase per-test-case average-cost SQL query")
    void buildsAvgCostPerTestCaseSqlForExecutionPhase() {
        String sql = builder.buildAvgCostPerTestCaseSql(RUN_ID, TracingConstants.PHASE_EXECUTION);

        assertThat(sql).isEqualTo(expectedAvgCostPerTestCaseSql(RUN_ID, TracingConstants.PHASE_EXECUTION));
    }

    @Test
    @DisplayName("builds the metric-evaluation-phase per-test-case average-cost SQL query with a different phase tag")
    void buildsAvgCostPerTestCaseSqlForMetricEvaluationPhase() {
        String sql = builder.buildAvgCostPerTestCaseSql(RUN_ID, TracingConstants.PHASE_METRIC_EVALUATION);

        assertThat(sql).isEqualTo(expectedAvgCostPerTestCaseSql(RUN_ID, TracingConstants.PHASE_METRIC_EVALUATION));
    }

    @Test
    @DisplayName("rejects a phase value other than the two known TracingConstants phases")
    void rejectsUnrecognizedPhase() {
        assertThatThrownBy(() -> builder.buildAvgCostPerTestCaseSql(RUN_ID, "bogus-phase"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static String expectedAvgCostPerTestCaseSql(UUID runId, String phase) {
        return String.join(
                "\n",
                "WITH tagged AS (",
                "  SELECT total_price, array_join(split_string(\"usage_request_baggage.baggage\", ',')) AS"
                        + " testcase_tag",
                "  FROM dial_usage_log",
                "  WHERE contains(\"usage_request_baggage.baggage\", 'eval.run.id=" + runId + "')",
                "    AND contains(\"usage_request_baggage.baggage\", 'eval.phase=" + phase + "')",
                "),",
                "per_testcase AS (",
                "  SELECT testcase_tag, sum(total_price) AS case_cost",
                "  FROM tagged",
                "  WHERE starts_with(testcase_tag, 'testcase.id=')",
                "  GROUP BY testcase_tag",
                ")",
                "SELECT avg(case_cost) AS avg_cost, count(*) AS count",
                "FROM per_testcase");
    }

    @Test
    @DisplayName("builds the execution-phase deployment-scoped aggregate query as a typed StructuredQuery")
    void buildsDeploymentExecutionPhaseQuery() {
        StructuredQuery query =
                builder.buildDeploymentAggregateQuery(DEPLOYMENT_ID, FROM_MS, TO_MS, TracingConstants.PHASE_EXECUTION);

        assertThat(query.entity()).isEqualTo("dial_usage_log");
        assertThat(query.mode()).isEqualTo(QueryMode.AGGREGATE);
        assertThat(query.groupBy()).isEmpty();
        assertThat(query.filter())
                .isEqualTo(new LogicalNode(
                        LogicalOp.AND,
                        List.of(
                                new ComparisonNode(
                                        ComparisonOp.EQ,
                                        List.of(
                                                new FieldExpr("deployment"),
                                                new ValueExpr(ValueType.STRING, DEPLOYMENT_ID))),
                                new ComparisonNode(
                                        ComparisonOp.GE,
                                        List.of(
                                                new FieldExpr("request_time"),
                                                new ValueExpr(ValueType.TIMESTAMP, String.valueOf(FROM_MS)))),
                                new ComparisonNode(
                                        ComparisonOp.LE,
                                        List.of(
                                                new FieldExpr("request_time"),
                                                new ValueExpr(ValueType.TIMESTAMP, String.valueOf(TO_MS)))),
                                baggageContains(
                                        TracingConstants.EVAL_PHASE + "=" + TracingConstants.PHASE_EXECUTION))));
        assertThat(query.select())
                .containsExactly(
                        new OutputColumn(new FnExpr("count", false, List.of()), null),
                        new OutputColumn(
                                new FnExpr("sum", false, List.of(new FieldExpr("total_price"))), "total_cost"));
    }

    @Test
    @DisplayName(
            "builds the deployment-scoped metric-evaluation-phase query with the same shape but a different phase value")
    void buildsDeploymentMetricEvaluationPhaseQuery() {
        StructuredQuery query = builder.buildDeploymentAggregateQuery(
                DEPLOYMENT_ID, FROM_MS, TO_MS, TracingConstants.PHASE_METRIC_EVALUATION);

        assertThat(query.filter())
                .isEqualTo(new LogicalNode(
                        LogicalOp.AND,
                        List.of(
                                new ComparisonNode(
                                        ComparisonOp.EQ,
                                        List.of(
                                                new FieldExpr("deployment"),
                                                new ValueExpr(ValueType.STRING, DEPLOYMENT_ID))),
                                new ComparisonNode(
                                        ComparisonOp.GE,
                                        List.of(
                                                new FieldExpr("request_time"),
                                                new ValueExpr(ValueType.TIMESTAMP, String.valueOf(FROM_MS)))),
                                new ComparisonNode(
                                        ComparisonOp.LE,
                                        List.of(
                                                new FieldExpr("request_time"),
                                                new ValueExpr(ValueType.TIMESTAMP, String.valueOf(TO_MS)))),
                                baggageContains(TracingConstants.EVAL_PHASE + "="
                                        + TracingConstants.PHASE_METRIC_EVALUATION))));
    }

    @Test
    @DisplayName("deployment-scoped query serializes to the exact JSON dial-adas expects on the wire")
    void serializesDeploymentQueryToDialAdasWireShape() {
        // Mirrors the production JsonMapper bean's default inclusion (JsonMapperConfiguration.createJsonMapper).
        JsonMapper objectMapper = JsonMapper.builder()
                .changeDefaultPropertyInclusion(
                        v -> JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
                .build();

        StructuredQuery query =
                builder.buildDeploymentAggregateQuery(DEPLOYMENT_ID, FROM_MS, TO_MS, TracingConstants.PHASE_EXECUTION);
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
                        "op": "eq",
                        "args": [
                          { "type": "field", "name": "deployment" },
                          { "type": "value", "value_type": "string", "value": "gpt-4o-2024-08-06" }
                        ]
                      },
                      {
                        "op": "ge",
                        "args": [
                          { "type": "field", "name": "request_time" },
                          { "type": "value", "value_type": "timestamp", "value": "1735689600000" }
                        ]
                      },
                      {
                        "op": "le",
                        "args": [
                          { "type": "field", "name": "request_time" },
                          { "type": "value", "value_type": "timestamp", "value": "1738368000000" }
                        ]
                      },
                      {
                        "op": "co",
                        "args": [
                          { "type": "field", "name": "usage_request_baggage.baggage" },
                          { "type": "value", "value_type": "string", "value": "eval.phase=execution" }
                        ]
                      }
                    ]
                  },
                  "group_by": [],
                  "select": [
                    { "expr": { "type": "fn", "name": "count", "distinct": false, "args": [] } },
                    { "expr": { "type": "fn", "name": "sum", "distinct": false, "args": [ { "type": "field", "name": "total_price" } ] }, "as": "total_cost" }
                  ]
                }
                """);

        assertThat(actual).isEqualTo(expected);
    }

    private static ComparisonNode baggageContains(String substring) {
        return new ComparisonNode(
                ComparisonOp.CO,
                List.of(new FieldExpr("usage_request_baggage.baggage"), new ValueExpr(ValueType.STRING, substring)));
    }

    @Test
    @DisplayName("builds a page total-cost query with an OR filter and case-derived run_id, no phase filter")
    void buildsPageTotalCostQueryForMultipleRuns() {
        UUID otherRunId = UUID.fromString("2a25d443-a673-47cf-a766-47b752118925");
        StructuredQuery query = builder.buildPageTotalCostQuery(List.of(RUN_ID, otherRunId));

        assertThat(query.entity()).isEqualTo("dial_usage_log");
        assertThat(query.mode()).isEqualTo(QueryMode.AGGREGATE);
        assertThat(query.groupBy()).containsExactly("run_id");
        assertThat(query.filter())
                .isEqualTo(new LogicalNode(
                        LogicalOp.OR,
                        List.of(
                                baggageContains(TracingConstants.EVAL_RUN_ID + "=" + RUN_ID),
                                baggageContains(TracingConstants.EVAL_RUN_ID + "=" + otherRunId))));
        assertThat(query.select())
                .containsExactly(
                        new OutputColumn(
                                new CaseExpr(
                                        List.of(
                                                new WhenClause(
                                                        baggageContains(TracingConstants.EVAL_RUN_ID + "=" + RUN_ID),
                                                        new ValueExpr(ValueType.STRING, RUN_ID.toString())),
                                                new WhenClause(
                                                        baggageContains(
                                                                TracingConstants.EVAL_RUN_ID + "=" + otherRunId),
                                                        new ValueExpr(ValueType.STRING, otherRunId.toString()))),
                                        new ValueExpr(ValueType.STRING, "other")),
                                "run_id"),
                        new OutputColumn(new FnExpr("count", false, List.of()), null),
                        new OutputColumn(
                                new FnExpr("sum", false, List.of(new FieldExpr("total_price"))), "total_cost"));
    }

    @Test
    @DisplayName("unwraps the OR node into a single predicate when exactly one run id is requested")
    void buildsPageTotalCostQueryForSingleRun() {
        StructuredQuery query = builder.buildPageTotalCostQuery(List.of(RUN_ID));

        assertThat(query.filter()).isEqualTo(baggageContains(TracingConstants.EVAL_RUN_ID + "=" + RUN_ID));
    }

    @Test
    @DisplayName("page total-cost query serializes to the exact JSON dial-adas expects on the wire")
    void serializesPageTotalCostQueryToDialAdasWireShape() {
        JsonMapper objectMapper = JsonMapper.builder()
                .changeDefaultPropertyInclusion(
                        v -> JsonInclude.Value.construct(JsonInclude.Include.NON_NULL, JsonInclude.Include.NON_NULL))
                .build();

        UUID otherRunId = UUID.fromString("2a25d443-a673-47cf-a766-47b752118925");
        StructuredQuery query = builder.buildPageTotalCostQuery(List.of(RUN_ID, otherRunId));
        JsonNode actual = objectMapper.valueToTree(query);

        JsonNode expected = objectMapper.readTree("""
                {
                  "entity": "dial_usage_log",
                  "mode": "aggregate",
                  "distinct": false,
                  "filter": {
                    "op": "or",
                    "args": [
                      {
                        "op": "co",
                        "args": [
                          { "type": "field", "name": "usage_request_baggage.baggage" },
                          { "type": "value", "value_type": "string", "value": "eval.run.id=1f810de3-cb9b-4e50-b9c5-794c41d99f6c" }
                        ]
                      },
                      {
                        "op": "co",
                        "args": [
                          { "type": "field", "name": "usage_request_baggage.baggage" },
                          { "type": "value", "value_type": "string", "value": "eval.run.id=2a25d443-a673-47cf-a766-47b752118925" }
                        ]
                      }
                    ]
                  },
                  "group_by": ["run_id"],
                  "select": [
                    {
                      "expr": {
                        "type": "case",
                        "when": [
                          {
                            "when": {
                              "op": "co",
                              "args": [
                                { "type": "field", "name": "usage_request_baggage.baggage" },
                                { "type": "value", "value_type": "string", "value": "eval.run.id=1f810de3-cb9b-4e50-b9c5-794c41d99f6c" }
                              ]
                            },
                            "then": { "type": "value", "value_type": "string", "value": "1f810de3-cb9b-4e50-b9c5-794c41d99f6c" }
                          },
                          {
                            "when": {
                              "op": "co",
                              "args": [
                                { "type": "field", "name": "usage_request_baggage.baggage" },
                                { "type": "value", "value_type": "string", "value": "eval.run.id=2a25d443-a673-47cf-a766-47b752118925" }
                              ]
                            },
                            "then": { "type": "value", "value_type": "string", "value": "2a25d443-a673-47cf-a766-47b752118925" }
                          }
                        ],
                        "else": { "type": "value", "value_type": "string", "value": "other" }
                      },
                      "as": "run_id"
                    },
                    { "expr": { "type": "fn", "name": "count", "distinct": false, "args": [] } },
                    { "expr": { "type": "fn", "name": "sum", "distinct": false, "args": [ { "type": "field", "name": "total_price" } ] }, "as": "total_cost" }
                  ]
                }
                """);

        assertThat(actual).isEqualTo(expected);
    }
}
