package com.epam.aidial.evaluation.experimental.query.model;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.configuration.JsonMapperConfiguration;
import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

/**
 * Round-trips the worked examples from {@code docs/experimental/structured-query-model.md} through
 * the production {@link JsonMapper}. This is the real proof that record binding, the {@code
 * type} discriminator on {@link Expr}, the {@link FilterNodeDeserializer} routing, and the
 * snake_case / {@code @JsonValue} wiring all work together — a clean compile does not prove Jackson
 * binds these records.
 */
class StructuredQueryDeserializationTest {

    private final JsonMapper mapper = new JsonMapperConfiguration().objectMapper();

    @Test
    void deserializesNestedBooleanFilterTree() {
        // §3 shape on this project's entity:
        // (execution_status = 'SUCCESS' AND accuracy_score > 0.8)
        //   OR (execution_status = 'PARTIAL' AND relevance_score > 0.5)
        // Routed through the real production site (StructuredQuery.filter).
        String json = """
                {
                  "entity": "test_case_eval_summaries",
                  "filter": {
                    "op": "or",
                    "args": [
                      { "op": "and", "args": [
                          { "op": "eq", "args": [ { "type": "field", "name": "execution_status" }, { "type": "value", "value_type": "string",  "value": "SUCCESS" } ] },
                          { "op": "gt", "args": [ { "type": "field", "name": "accuracy_score" },   { "type": "value", "value_type": "decimal", "value": "0.8" } ] }
                      ]},
                      { "op": "and", "args": [
                          { "op": "eq", "args": [ { "type": "field", "name": "execution_status" }, { "type": "value", "value_type": "string",  "value": "PARTIAL" } ] },
                          { "op": "gt", "args": [ { "type": "field", "name": "relevance_score" },  { "type": "value", "value_type": "decimal", "value": "0.5" } ] }
                      ]}
                    ]
                  }
                }
                """;

        FilterNode node = mapper.readValue(json, StructuredQuery.class).filter();

        assertThat(node).isInstanceOf(LogicalNode.class);
        LogicalNode root = (LogicalNode) node;
        assertThat(root.op()).isEqualTo(LogicalOp.OR);
        assertThat(root.args()).hasSize(2).allSatisfy(child -> assertThat(child).isInstanceOf(LogicalNode.class));

        LogicalNode left = (LogicalNode) root.args().get(0);
        assertThat(left.op()).isEqualTo(LogicalOp.AND);

        ComparisonNode statusEq = (ComparisonNode) left.args().get(0);
        assertThat(statusEq.op()).isEqualTo(ComparisonOp.EQ);
        assertThat(statusEq.args())
                .containsExactly(new FieldExpr("execution_status"), new ValueExpr(ValueType.STRING, "SUCCESS"));

        ComparisonNode accuracyGt = (ComparisonNode) left.args().get(1);
        assertThat(accuracyGt.op()).isEqualTo(ComparisonOp.GT);
        assertThat(accuracyGt.args())
                .containsExactly(new FieldExpr("accuracy_score"), new ValueExpr(ValueType.DECIMAL, "0.8"));
    }

    @Test
    void deserializesComparisonWithFunctionOnLeftSide() {
        // length(test_suite_id) = 3 — neither side is a bare column; LHS is fn(field), RHS is a value.
        String json = """
                {
                  "entity": "test_case_eval_summaries",
                  "filter": {
                    "op": "eq",
                    "args": [
                      { "type": "fn", "name": "length", "args": [ { "type": "field", "name": "test_suite_id" } ] },
                      { "type": "value", "value_type": "integer", "value": "3" }
                    ]
                  }
                }
                """;

        ComparisonNode node =
                (ComparisonNode) mapper.readValue(json, StructuredQuery.class).filter();

        assertThat(node.op()).isEqualTo(ComparisonOp.EQ);
        assertThat(node.args())
                .containsExactly(
                        new FnExpr("length", false, List.of(new FieldExpr("test_suite_id"))),
                        new ValueExpr(ValueType.INTEGER, "3"));
    }

    @Test
    void deserializesNullCheckPredicate() {
        // §4.2 / §8.3: accuracy_score IS NULL — a metric-derived column with no score resolves to NULL.
        String json = """
                {
                  "entity": "test_case_eval_summaries",
                  "filter": { "op": "eq", "args": [ { "type": "field", "name": "accuracy_score" }, { "type": "value", "value_type": "null", "value": null } ] }
                }
                """;

        ComparisonNode node =
                (ComparisonNode) mapper.readValue(json, StructuredQuery.class).filter();

        assertThat(node.op()).isEqualTo(ComparisonOp.EQ);
        assertThat(node.args()).containsExactly(new FieldExpr("accuracy_score"), new ValueExpr(ValueType.NULL, null));
    }

    @Test
    void deserializesInPredicateWithArrayOperand() {
        // §8.5: execution_status IN ('SUCCESS', 'PARTIAL') — in is an ordinary binary predicate
        // whose right operand is an `array` expression (§4.6).
        String json = """
                {
                  "entity": "test_case_eval_summaries",
                  "filter": {
                    "op": "in",
                    "args": [
                      { "type": "field", "name": "execution_status" },
                      { "type": "array", "items": [
                          { "type": "value", "value_type": "string", "value": "SUCCESS" },
                          { "type": "value", "value_type": "string", "value": "PARTIAL" }
                      ]}
                    ]
                  }
                }
                """;

        ComparisonNode node =
                (ComparisonNode) mapper.readValue(json, StructuredQuery.class).filter();

        assertThat(node.op()).isEqualTo(ComparisonOp.IN);
        assertThat(node.args())
                .containsExactly(
                        new FieldExpr("execution_status"),
                        new ArrayExpr(List.of(
                                new ValueExpr(ValueType.STRING, "SUCCESS"),
                                new ValueExpr(ValueType.STRING, "PARTIAL"))));
    }

    @Test
    void deserializesNestedFunctionExpression() {
        // §4.1: round(div(sum(accuracy_score), count()), 2)
        String json = """
                { "type": "fn", "name": "round", "args": [
                    { "type": "fn", "name": "div", "args": [
                        { "type": "fn", "name": "sum",   "args": [ { "type": "field", "name": "accuracy_score" } ] },
                        { "type": "fn", "name": "count", "args": [] }
                    ]},
                    { "type": "value", "value_type": "integer", "value": "2" }
                ]}
                """;

        FnExpr round = (FnExpr) mapper.readValue(json, Expr.class);
        assertThat(round.name()).isEqualTo("round");
        assertThat(round.args()).hasSize(2);

        FnExpr div = (FnExpr) round.args().get(0);
        assertThat(div.name()).isEqualTo("div");
        FnExpr sum = (FnExpr) div.args().get(0);
        assertThat(sum.name()).isEqualTo("sum");
        assertThat(sum.args()).containsExactly(new FieldExpr("accuracy_score"));
        FnExpr count = (FnExpr) div.args().get(1);
        assertThat(count.args()).isEmpty();

        assertThat(round.args().get(1)).isEqualTo(new ValueExpr(ValueType.INTEGER, "2"));
    }

    @Test
    void deserializesFullRowModeEnvelope() {
        // §8.1 row-mode query with offset page + include_total.
        String json = """
                {
                  "entity": "test_case_eval_summaries",
                  "mode": "row",
                  "filter": {
                    "op": "lt",
                    "args": [ { "type": "field", "name": "accuracy_score" }, { "type": "value", "value_type": "decimal", "value": "0.5" } ]
                  },
                  "select": [
                    { "expr": { "type": "field", "name": "id" } },
                    { "expr": { "type": "field", "name": "accuracy_score" } }
                  ],
                  "sort": [ { "field": "accuracy_score", "dir": "asc", "nulls": "last" }, { "field": "id", "dir": "asc" } ],
                  "page": { "type": "offset", "offset": 0, "limit": 25, "include_total": true }
                }
                """;

        StructuredQuery query = mapper.readValue(json, StructuredQuery.class);

        assertThat(query.entity()).isEqualTo("test_case_eval_summaries");
        assertThat(query.mode()).isEqualTo(QueryMode.ROW);
        assertThat(query.filter()).isInstanceOf(ComparisonNode.class);
        assertThat(query.select())
                .containsExactly(
                        new OutputColumn(new FieldExpr("id"), null),
                        new OutputColumn(new FieldExpr("accuracy_score"), null));
        assertThat(query.sort())
                .containsExactly(
                        new SortItem("accuracy_score", SortDir.ASC, NullsOrder.LAST),
                        new SortItem("id", SortDir.ASC, null));
        assertThat(query.page()).isEqualTo(new OffsetPage(0L, 25, true));
    }

    @Test
    void deserializesFullAggregateModeEnvelope() {
        // §8.2 aggregate-mode query with group_by, fn expressions in select (alias `as`), having, cursor page.
        String json = """
                {
                  "entity": "test_case_eval_summaries",
                  "mode": "aggregate",
                  "group_by": ["execution_status"],
                  "select": [
                    { "expr": { "type": "field", "name": "execution_status" } },
                    { "expr": { "type": "fn", "name": "count", "args": [] }, "as": "total_cases" },
                    { "expr": { "type": "fn", "name": "avg", "args": [ { "type": "field", "name": "accuracy_score" } ] }, "as": "avg_accuracy_score" }
                  ],
                  "having": { "op": "ge", "args": [ { "type": "field", "name": "avg_accuracy_score" }, { "type": "value", "value_type": "decimal", "value": "0.6" } ] },
                  "sort": [ { "field": "avg_accuracy_score", "dir": "desc" } ],
                  "page": { "type": "cursor", "limit": 100 }
                }
                """;

        StructuredQuery query = mapper.readValue(json, StructuredQuery.class);

        assertThat(query.mode()).isEqualTo(QueryMode.AGGREGATE);
        assertThat(query.groupBy()).containsExactly("execution_status");
        assertThat(query.select())
                .containsExactly(
                        new OutputColumn(new FieldExpr("execution_status"), null),
                        new OutputColumn(new FnExpr("count", false, List.of()), "total_cases"),
                        new OutputColumn(
                                new FnExpr("avg", false, List.of(new FieldExpr("accuracy_score"))),
                                "avg_accuracy_score"));
        assertThat(query.having()).isInstanceOf(ComparisonNode.class);
        assertThat(((ComparisonNode) query.having()).args().get(0)).isEqualTo(new FieldExpr("avg_accuracy_score"));
        assertThat(query.page()).isEqualTo(new CursorPage(null, 100));
    }

    @Test
    void roundTripsEnvelopeThroughSerialization() {
        // deserialize -> serialize -> deserialize must be stable (discriminators + snake_case keys).
        String json = """
                {
                  "entity": "test_case_eval_summaries",
                  "mode": "row",
                  "filter": { "op": "in", "args": [
                      { "type": "field", "name": "execution_status" },
                      { "type": "array", "items": [
                          { "type": "value", "value_type": "string", "value": "SUCCESS" },
                          { "type": "value", "value_type": "string", "value": "PARTIAL" }
                      ]}
                  ]},
                  "page": { "type": "offset", "offset": 200, "limit": 100, "include_total": false }
                }
                """;

        StructuredQuery first = mapper.readValue(json, StructuredQuery.class);
        String reserialized = mapper.writeValueAsString(first);
        StructuredQuery second = mapper.readValue(reserialized, StructuredQuery.class);

        assertThat(second).isEqualTo(first);
        assertThat(reserialized)
                .contains("\"op\":\"in\"")
                .contains("\"type\":\"field\"")
                .contains("\"name\":\"execution_status\"")
                .contains("\"type\":\"array\"")
                .contains("\"items\":[");
    }
}
