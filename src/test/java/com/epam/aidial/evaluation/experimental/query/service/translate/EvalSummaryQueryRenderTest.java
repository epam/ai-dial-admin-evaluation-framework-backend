package com.epam.aidial.evaluation.experimental.query.service.translate;

import static com.epam.aidial.evaluation.data.db.jooq.analytics.Tables.TEST_CASE_EVAL_SUMMARIES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.data.db.repository.sql.json.PostgresJsonPathAccessor;
import com.epam.aidial.evaluation.experimental.query.model.ArrayExpr;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonNode;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonOp;
import com.epam.aidial.evaluation.experimental.query.model.Expr;
import com.epam.aidial.evaluation.experimental.query.model.FieldExpr;
import com.epam.aidial.evaluation.experimental.query.model.FilterNode;
import com.epam.aidial.evaluation.experimental.query.model.FnExpr;
import com.epam.aidial.evaluation.experimental.query.model.OffsetPage;
import com.epam.aidial.evaluation.experimental.query.model.OutputColumn;
import com.epam.aidial.evaluation.experimental.query.model.QueryMode;
import com.epam.aidial.evaluation.experimental.query.model.SortDir;
import com.epam.aidial.evaluation.experimental.query.model.SortItem;
import com.epam.aidial.evaluation.experimental.query.model.StructuredQuery;
import com.epam.aidial.evaluation.experimental.query.model.ValueExpr;
import com.epam.aidial.evaluation.experimental.query.model.ValueType;
import com.epam.aidial.evaluation.experimental.query.service.JooqTableSchemaResolver;
import com.epam.aidial.evaluation.experimental.query.service.QueryFieldBinding;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Renders the SQL produced for the analytics {@code eval_summaries} entity without a database. The
 * translation machinery is shared with {@code test_suites}; these cases prove the field-name → column
 * mapping for the analytics table.
 */
class EvalSummaryQueryRenderTest {

    private final DSLContext dsl = DSL.using(SQLDialect.POSTGRES);
    private final Map<String, QueryFieldBinding> bindings =
            new JooqTableSchemaResolver().bindings(TEST_CASE_EVAL_SUMMARIES);

    private final ValueExprToObjectMapper valueExprToObjectMapper = new ValueExprToObjectMapper();
    private final JsonbFieldResolver jsonbFieldResolver = new JsonbFieldResolver(new PostgresJsonPathAccessor());
    private final ExprTranslator exprTranslator = new ExprTranslator(valueExprToObjectMapper, jsonbFieldResolver);
    private final FilterTranslator filterTranslator = new FilterTranslator(exprTranslator);
    private final StructuredQueryBuilder builder = new StructuredQueryBuilder(exprTranslator, filterTranslator);

    private String render(StructuredQuery query) {
        return dsl.renderInlined(builder.build(dsl, TEST_CASE_EVAL_SUMMARIES, bindings, query))
                .toLowerCase(Locale.ROOT);
    }

    @Test
    @DisplayName("maps raw column field names to their analytics columns in filter and sort")
    void mapsFieldNamesToColumns() {
        StructuredQuery query = new StructuredQuery(
                "eval_summaries",
                new ComparisonNode(
                        ComparisonOp.EQ,
                        List.of(new FieldExpr("execution_status"), new ValueExpr(ValueType.STRING, "SUCCESS"))),
                QueryMode.ROW,
                false,
                List.of(
                        new OutputColumn(new FieldExpr("test_case_name"), null),
                        new OutputColumn(new FieldExpr("exec_duration_ms"), null)),
                null,
                null,
                List.of(new SortItem("created_at_ms", SortDir.DESC)),
                new OffsetPage(0, 50, false));

        String sql = render(query);
        assertThat(sql)
                .contains("\"execution_status\" = 'success'")
                .contains("\"test_case_name\"")
                .contains("\"exec_duration_ms\"")
                .contains("\"created_at_ms\" desc");
    }

    @Test
    @DisplayName("renders count/min/max/avg over a run grouped by execution status")
    void rendersAggregatesGroupedByStatus() {
        StructuredQuery query = new StructuredQuery(
                "eval_summaries",
                null,
                QueryMode.AGGREGATE,
                false,
                List.of(
                        new OutputColumn(new FieldExpr("execution_status"), null),
                        new OutputColumn(new FnExpr("count", false, List.of()), "total"),
                        new OutputColumn(
                                new FnExpr("min", false, List.of(new FieldExpr("exec_duration_ms"))), "fastest"),
                        new OutputColumn(new FnExpr("avg", false, List.of(new FieldExpr("exec_duration_ms"))), "mean")),
                List.of("execution_status"),
                null,
                null,
                new OffsetPage(0, 50, false));

        String sql = render(query);
        assertThat(sql)
                .contains("count(*)")
                .contains("min(\"analytics\".\"test_case_eval_summaries\".\"exec_duration_ms\")")
                .contains("avg(\"analytics\".\"test_case_eval_summaries\".\"exec_duration_ms\")")
                .contains("group by")
                .contains("\"execution_status\"");
    }

    @Test
    @DisplayName("renders width_bucket(operand, low, high, count) as a histogram bucket over a duration")
    void rendersWidthBucketOverDuration() {
        StructuredQuery query = new StructuredQuery(
                "eval_summaries",
                null,
                QueryMode.ROW,
                false,
                List.of(new OutputColumn(
                        new FnExpr(
                                "width_bucket",
                                false,
                                List.of(
                                        new FieldExpr("exec_duration_ms"),
                                        new ValueExpr(ValueType.LONG, "0"),
                                        new ValueExpr(ValueType.LONG, "1000"),
                                        new ValueExpr(ValueType.INTEGER, "10"))),
                        "bucket")),
                null,
                null,
                null,
                new OffsetPage(0, 50, false));

        String sql = render(query);
        assertThat(sql)
                .contains("width_bucket(")
                .contains("\"exec_duration_ms\"")
                .contains("\"bucket\"");
    }

    @Test
    @DisplayName("groups by a computed width_bucket select alias over a metric field")
    void groupsByComputedSelectAlias() {
        FnExpr bucket = new FnExpr(
                "width_bucket",
                false,
                List.of(
                        new FieldExpr("metric:DeepEval Answer Relevancy:score"),
                        new ValueExpr(ValueType.DECIMAL, "0"),
                        new ValueExpr(ValueType.DECIMAL, "1"),
                        new ValueExpr(ValueType.INTEGER, "5")));
        StructuredQuery query = new StructuredQuery(
                "eval_summaries",
                null,
                QueryMode.AGGREGATE,
                false,
                List.of(
                        new OutputColumn(bucket, "bucket"),
                        new OutputColumn(new FnExpr("count", false, List.of()), "cnt")),
                List.of("bucket"),
                null,
                null,
                new OffsetPage(0, 50, false));

        String sql = render(query);
        // The group key "bucket" is the computed select alias. It must group by the OUTPUT column
        // reference ("bucket"), not re-inline the width_bucket expression: re-inlining would emit fresh
        // bind parameters for the JSONB keys and PostgreSQL would reject the mismatch.
        assertThat(sql)
                .contains("width_bucket(")
                .contains("\"metric_values\"")
                .contains("count(*)")
                .contains("group by \"bucket\"")
                .doesNotContain("group by width_bucket(");
    }

    @Test
    @DisplayName("resolves a flattened metric field to a two-level numeric JSONB path")
    void resolvesMetricFieldToNumericJsonbPath() {
        StructuredQuery query = new StructuredQuery(
                "eval_summaries",
                new ComparisonNode(
                        ComparisonOp.GT,
                        List.of(
                                new FieldExpr("metric:Exact Match1:exact_match"),
                                new ValueExpr(ValueType.DECIMAL, "0.5"))),
                QueryMode.ROW,
                false,
                List.of(new OutputColumn(new FieldExpr("metric:Exact Match1:exact_match"), null)),
                null,
                null,
                null,
                new OffsetPage(0, 50, false));

        String sql = render(query);
        // metric_values -> 'Exact Match1' ->> 'exact_match' cast to numeric (keys are bound, lowercased here).
        assertThat(sql)
                .contains("\"metric_values\"")
                .contains("'exact match1'")
                .contains("'exact_match'")
                .contains("numeric");
    }

    @Test
    @DisplayName("resolves a flattened data field to a JSONB text path usable with LIKE")
    void resolvesDataFieldToJsonbText() {
        StructuredQuery query = new StructuredQuery(
                "eval_summaries",
                new ComparisonNode(
                        ComparisonOp.CO,
                        List.of(new FieldExpr("data:question"), new ValueExpr(ValueType.STRING, "what"))),
                QueryMode.ROW,
                false,
                null,
                null,
                null,
                null,
                new OffsetPage(0, 50, false));

        String sql = render(query);
        assertThat(sql).contains("\"test_case_data\"").contains("'question'").contains("like");
    }

    @Test
    @DisplayName("translates 'in' over run ids into an IN list on the uuid column")
    void translatesInOverRunIds() {
        FilterNode filter = new ComparisonNode(
                ComparisonOp.IN,
                List.of(
                        new FieldExpr("test_suite_run_id"),
                        new ArrayExpr(List.<Expr>of(
                                new ValueExpr(ValueType.UUID, "00000000-0000-0000-0000-000000000001"),
                                new ValueExpr(ValueType.UUID, "00000000-0000-0000-0000-000000000002")))));
        StructuredQuery query = new StructuredQuery(
                "eval_summaries", filter, QueryMode.ROW, false, null, null, null, null, new OffsetPage(0, 50, false));

        String sql = render(query);
        assertThat(sql).contains("\"test_suite_run_id\" in (");
    }

    @Test
    @DisplayName("renders percentile_cont(fraction, column) as an ordered-set aggregate over metric scores")
    void rendersPercentileContWithinGroup() {
        StructuredQuery query = new StructuredQuery(
                "eval_summaries",
                null,
                QueryMode.AGGREGATE,
                false,
                List.of(
                        new OutputColumn(
                                percentile("percentile_cont", "0.1", "metric:Ragas Answer Relevancy:score"), "p10"),
                        new OutputColumn(
                                percentile("percentile_cont", "0.9", "metric:DeepEval Answer Relevancy:score"), "p90")),
                List.of(),
                null,
                null,
                new OffsetPage(0, 50, false));

        String sql = render(query);
        assertThat(sql)
                .contains("percentile_cont(")
                .contains("within group (order by")
                .contains("\"metric_values\"")
                .contains("numeric")
                .contains("\"p10\"")
                .contains("\"p90\"");
    }

    @Test
    @DisplayName("renders percentile_disc(fraction, column) as an ordered-set aggregate")
    void rendersPercentileDiscWithinGroup() {
        StructuredQuery query = new StructuredQuery(
                "eval_summaries",
                null,
                QueryMode.AGGREGATE,
                false,
                List.of(new OutputColumn(percentile("percentile_disc", "0.5", "exec_duration_ms"), "median_ms")),
                List.of(),
                null,
                null,
                new OffsetPage(0, 50, false));

        String sql = render(query);
        assertThat(sql)
                .contains("percentile_disc(")
                .contains("within group (order by")
                .contains("\"exec_duration_ms\"")
                .contains("\"median_ms\"");
    }

    @Test
    @DisplayName("rejects a percentile call that does not have exactly two arguments")
    void rejectsPercentileWrongArity() {
        StructuredQuery query = aggregateSelecting(
                new FnExpr("percentile_cont", false, List.of(new ValueExpr(ValueType.DECIMAL, "0.5"))));

        assertThatThrownBy(() -> render(query))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("two arguments");
    }

    @Test
    @DisplayName("rejects a percentile fraction outside [0, 1]")
    void rejectsPercentileFractionOutOfRange() {
        StructuredQuery query = aggregateSelecting(percentile("percentile_cont", "1.5", "exec_duration_ms"));

        assertThatThrownBy(() -> render(query))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("[0, 1]");
    }

    @Test
    @DisplayName("rejects a non-numeric percentile fraction literal")
    void rejectsPercentileNonNumericFraction() {
        StructuredQuery query = aggregateSelecting(new FnExpr(
                "percentile_cont",
                false,
                List.of(new ValueExpr(ValueType.STRING, "half"), new FieldExpr("exec_duration_ms"))));

        assertThatThrownBy(() -> render(query))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("numeric literal");
    }

    private static FnExpr percentile(String fn, String fraction, String column) {
        return new FnExpr(fn, false, List.of(new ValueExpr(ValueType.DECIMAL, fraction), new FieldExpr(column)));
    }

    private static StructuredQuery aggregateSelecting(Expr expr) {
        return new StructuredQuery(
                "eval_summaries",
                null,
                QueryMode.AGGREGATE,
                false,
                List.of(new OutputColumn(expr, "value")),
                List.of(),
                null,
                null,
                new OffsetPage(0, 50, false));
    }
}
