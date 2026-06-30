package com.epam.aidial.evaluation.experimental.query.service.translate;

import static com.epam.aidial.evaluation.data.db.jooq.meta.Tables.TEST_SUITES;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.data.db.repository.sql.json.PostgresJsonPathAccessor;
import com.epam.aidial.evaluation.experimental.query.model.ArrayExpr;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonNode;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonOp;
import com.epam.aidial.evaluation.experimental.query.model.CursorPage;
import com.epam.aidial.evaluation.experimental.query.model.Expr;
import com.epam.aidial.evaluation.experimental.query.model.FieldExpr;
import com.epam.aidial.evaluation.experimental.query.model.FilterNode;
import com.epam.aidial.evaluation.experimental.query.model.FnExpr;
import com.epam.aidial.evaluation.experimental.query.model.LogicalNode;
import com.epam.aidial.evaluation.experimental.query.model.LogicalOp;
import com.epam.aidial.evaluation.experimental.query.model.NullsOrder;
import com.epam.aidial.evaluation.experimental.query.model.OffsetPage;
import com.epam.aidial.evaluation.experimental.query.model.OutputColumn;
import com.epam.aidial.evaluation.experimental.query.model.ParamExpr;
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
 * Renders the SQL produced by the model → jOOQ translation layer for {@code test_suites} without a
 * database, asserting the generated clauses. Uses a render-only Postgres {@link DSLContext}.
 */
class StructuredQueryBuilderTest {

    private final DSLContext dsl = DSL.using(SQLDialect.POSTGRES);
    private final Map<String, QueryFieldBinding> bindings = new JooqTableSchemaResolver().bindings(TEST_SUITES);

    private final ValueExprToObjectMapper valueExprToObjectMapper = new ValueExprToObjectMapper();
    private final JsonbFieldResolver jsonbFieldResolver = new JsonbFieldResolver(new PostgresJsonPathAccessor());
    private final ExprTranslator exprTranslator = new ExprTranslator(
            valueExprToObjectMapper, jsonbFieldResolver, QueryFunctionTestSupport.registry(valueExprToObjectMapper));
    private final FilterTranslator filterTranslator = new FilterTranslator(exprTranslator);
    private final StructuredQueryBuilder builder = new StructuredQueryBuilder(exprTranslator, filterTranslator);
    private final QueryParameterResolver parameterResolver = new QueryParameterResolver();

    private String render(StructuredQuery query) {
        return dsl.renderInlined(builder.build(dsl, TEST_SUITES, bindings, query))
                .toLowerCase(Locale.ROOT);
    }

    private static FieldExpr field(String name) {
        return new FieldExpr(name);
    }

    private static ValueExpr value(ValueType type, String raw) {
        return new ValueExpr(type, raw);
    }

    private static OutputColumn col(Expr expr) {
        return new OutputColumn(expr, null);
    }

    private static ComparisonNode cmp(ComparisonOp op, Expr left, Expr right) {
        return new ComparisonNode(op, List.of(left, right));
    }

    private static StructuredQuery rowQuery(FilterNode filter, List<OutputColumn> select, List<SortItem> sort) {
        return new StructuredQuery(
                "test_suites", filter, QueryMode.ROW, false, select, null, null, sort, new OffsetPage(0, 50, false));
    }

    @Test
    @DisplayName("translates an eq comparison into a parameterised WHERE clause on the bound column")
    void translatesEqFilter() {
        String sql = render(rowQuery(cmp(ComparisonOp.EQ, field("name"), value(ValueType.STRING, "demo")), null, null));
        assertThat(sql).contains("\"name\" = 'demo'").contains("where");
    }

    @Test
    @DisplayName("translates 'in' into an IN list over the bound column")
    void translatesInFilter() {
        FilterNode filter = cmp(
                ComparisonOp.IN,
                field("suite_type"),
                new ArrayExpr(List.of(value(ValueType.STRING, "DEPLOYMENT"), value(ValueType.STRING, "ENDPOINT"))));
        String sql = render(rowQuery(filter, null, null));
        assertThat(sql).contains("\"suite_type\" in (").contains("'deployment'").contains("'endpoint'");
    }

    @Test
    @DisplayName("translates 'co' into a case-insensitive LIKE")
    void translatesContainsFilter() {
        String sql = render(rowQuery(cmp(ComparisonOp.CO, field("name"), value(ValueType.STRING, "abc")), null, null));
        assertThat(sql).contains("like").contains("%abc%");
    }

    @Test
    @DisplayName("translates eq against a null literal into IS NULL")
    void translatesNullCheck() {
        String sql =
                render(rowQuery(cmp(ComparisonOp.EQ, field("dataset_id"), value(ValueType.NULL, null)), null, null));
        assertThat(sql).contains("\"dataset_id\" is null");
    }

    @Test
    @DisplayName("composes and/or/not into nested boolean SQL")
    void translatesLogicalTree() {
        FilterNode tree = new LogicalNode(
                LogicalOp.AND,
                List.of(
                        cmp(ComparisonOp.EQ, field("is_valid"), value(ValueType.BOOLEAN, "true")),
                        new LogicalNode(
                                LogicalOp.NOT,
                                List.of(cmp(ComparisonOp.EQ, field("name"), value(ValueType.STRING, "skip"))))));
        String sql = render(rowQuery(tree, null, null));
        assertThat(sql).contains("\"is_valid\" = true").contains("not (").contains(" and ");
    }

    @Test
    @DisplayName("projects only the requested fields in row mode")
    void projectsSelectedFields() {
        StructuredQuery query = rowQuery(null, List.of(col(field("id")), col(field("name"))), null);
        String sql = render(query);
        assertThat(sql).contains("\"id\"").contains("\"name\"").doesNotContain("\"description\"");
    }

    @Test
    @DisplayName("emits ORDER BY and LIMIT/OFFSET for sort and offset paging")
    void emitsSortAndPaging() {
        StructuredQuery query = new StructuredQuery(
                "test_suites",
                null,
                QueryMode.ROW,
                false,
                null,
                null,
                null,
                List.of(new SortItem("created_at_ms", SortDir.DESC, null)),
                new OffsetPage(20, 25, false));
        String sql = render(query);
        assertThat(sql).contains("order by").contains("\"created_at_ms\" desc");
        // POSTGRES dialect renders SQL-standard paging: "offset 20 rows fetch next 25 rows only".
        assertThat(sql).contains("offset 20").contains("25 rows");
        // No client-supplied null ordering → no NULLS clause; DB default applies (D8).
        assertThat(sql).doesNotContain("nulls");
    }

    @Test
    @DisplayName("emits client-supplied NULLS FIRST/LAST ordering on sort keys")
    void emitsClientNullOrdering() {
        StructuredQuery descNullsLast = new StructuredQuery(
                "test_suites",
                null,
                QueryMode.ROW,
                false,
                null,
                null,
                null,
                List.of(new SortItem("created_at_ms", SortDir.DESC, NullsOrder.LAST)),
                null);
        assertThat(render(descNullsLast)).contains("\"created_at_ms\" desc nulls last");

        StructuredQuery ascNullsFirst = new StructuredQuery(
                "test_suites",
                null,
                QueryMode.ROW,
                false,
                null,
                null,
                null,
                List.of(new SortItem("created_at_ms", SortDir.ASC, NullsOrder.FIRST)),
                null);
        assertThat(render(ascNullsFirst)).contains("\"created_at_ms\" asc nulls first");
    }

    @Test
    @DisplayName("builds group_by + aggregate select entries + having in aggregate mode")
    void buildsAggregateQuery() {
        StructuredQuery query = new StructuredQuery(
                "test_suites",
                null,
                QueryMode.AGGREGATE,
                false,
                List.of(col(field("suite_type")), new OutputColumn(new FnExpr("count", false, List.of()), "total")),
                List.of("suite_type"),
                cmp(ComparisonOp.GT, field("total"), value(ValueType.LONG, "5")),
                null,
                new OffsetPage(0, 50, false));
        String sql = render(query);
        assertThat(sql)
                .contains("count(*)")
                .contains("group by")
                .contains("\"suite_type\"")
                .contains("having");
    }

    @Test
    @DisplayName("sorts by a select alias via its output-column reference, not by re-translating the expression")
    void sortsBySelectAliasViaOutputReference() {
        StructuredQuery query = new StructuredQuery(
                "test_suites",
                null,
                QueryMode.AGGREGATE,
                false,
                List.of(col(field("suite_type")), new OutputColumn(new FnExpr("count", false, List.of()), "total")),
                List.of("suite_type"),
                null,
                List.of(new SortItem("total", SortDir.DESC, null)),
                new OffsetPage(0, 50, false));
        String sql = render(query);
        assertThat(sql).contains("order by \"total\" desc").doesNotContain("order by count(*)");
    }

    @Test
    @DisplayName("rejects a select entry with a missing expression as a validation error, not a server fault")
    void rejectsSelectEntryWithMissingExpr() {
        StructuredQuery query = rowQuery(null, List.of(new OutputColumn(null, "x")), null);
        assertThatThrownBy(() -> builder.build(dsl, TEST_SUITES, bindings, query))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("expr");
    }

    private String renderAggregate(OutputColumn aggCol) {
        StructuredQuery query = new StructuredQuery(
                "test_suites",
                null,
                QueryMode.AGGREGATE,
                false,
                List.of(col(field("suite_type")), aggCol),
                List.of("suite_type"),
                null,
                null,
                new OffsetPage(0, 50, false));
        return render(query);
    }

    @Test
    @DisplayName("renders sum/avg/min/max over a numeric column with the requested alias")
    void rendersNumericAggregates() {
        assertThat(renderAggregate(new OutputColumn(new FnExpr("sum", false, List.of(field("version"))), "versionSum")))
                .contains("sum(\"meta\".\"test_suites\".\"version\")")
                .contains("\"versionsum\"");
        assertThat(renderAggregate(new OutputColumn(new FnExpr("avg", false, List.of(field("version"))), "versionAvg")))
                .contains("avg(\"meta\".\"test_suites\".\"version\")");
        assertThat(renderAggregate(
                        new OutputColumn(new FnExpr("min", false, List.of(field("created_at_ms"))), "earliest")))
                .contains("min(\"meta\".\"test_suites\".\"created_at_ms\")");
        assertThat(renderAggregate(
                        new OutputColumn(new FnExpr("max", false, List.of(field("created_at_ms"))), "latest")))
                .contains("max(\"meta\".\"test_suites\".\"created_at_ms\")");
    }

    @Test
    @DisplayName("renders DISTINCT aggregates")
    void rendersDistinctAggregate() {
        assertThat(renderAggregate(
                        new OutputColumn(new FnExpr("count", true, List.of(field("dataset_id"))), "datasets")))
                .contains("count(distinct \"meta\".\"test_suites\".\"dataset_id\")");
    }

    @Test
    @DisplayName("rejects an unsupported function name in a select aggregate")
    void rejectsUnsupportedAggregate() {
        assertThatThrownBy(() ->
                        renderAggregate(new OutputColumn(new FnExpr("median", false, List.of(field("version"))), "m")))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("unsupported function 'median'");
    }

    @Test
    @DisplayName("rejects an aggregate output column missing its 'as' alias")
    void rejectsAggregateWithoutAlias() {
        assertThatThrownBy(() ->
                        renderAggregate(new OutputColumn(new FnExpr("sum", false, List.of(field("version"))), null)))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("'as' alias");
    }

    @Test
    @DisplayName("rejects an unknown field name")
    void rejectsUnknownField() {
        StructuredQuery query =
                rowQuery(cmp(ComparisonOp.EQ, field("nonExisting"), value(ValueType.STRING, "x")), null, null);
        assertThatThrownBy(() -> builder.build(dsl, TEST_SUITES, bindings, query))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("unknown field 'nonExisting'");
    }

    @Test
    @DisplayName("rejects a flattened JSONB field on an entity that lacks the backing column")
    void rejectsFlattenedFieldWithoutBackingColumn() {
        // test_suites has no metric_values column, so the published metric: family is not resolvable here.
        StructuredQuery query = rowQuery(null, List.of(col(field("metric::Accuracy::score"))), null);
        assertThatThrownBy(() -> builder.build(dsl, TEST_SUITES, bindings, query))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("unknown field 'metric::Accuracy::score'");
    }

    @Test
    @DisplayName("rejects cursor pagination as unsupported")
    void rejectsCursorPaging() {
        StructuredQuery query = new StructuredQuery(
                "test_suites", null, QueryMode.ROW, false, null, null, null, null, new CursorPage(null, 25));
        assertThatThrownBy(() -> builder.build(dsl, TEST_SUITES, bindings, query))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cursor pagination");
    }

    @Test
    @DisplayName("rejects an unbound param expression when no binding is supplied")
    void rejectsUnboundParamExpression() {
        StructuredQuery query = rowQuery(cmp(ComparisonOp.EQ, field("name"), new ParamExpr("p")), null, null);
        assertThatThrownBy(() -> builder.build(dsl, TEST_SUITES, bindings, query))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("unbound query parameter 'p'");
    }

    @Test
    @DisplayName("substitutes a value-bound param as a literal operand")
    void substitutesValueBoundParam() {
        StructuredQuery query = rowQuery(cmp(ComparisonOp.EQ, field("name"), new ParamExpr("p")), null, null);
        String sql = renderWithParams(query, Map.of("p", value(ValueType.STRING, "demo")));
        assertThat(sql).contains("\"name\" = 'demo'");
    }

    @Test
    @DisplayName("substitutes a field-bound param as the bound column")
    void substitutesFieldBoundParam() {
        StructuredQuery query = rowQuery(cmp(ComparisonOp.EQ, field("name"), new ParamExpr("col")), null, null);
        String sql = renderWithParams(query, Map.of("col", field("description")));
        assertThat(sql).contains("\"name\" = ").contains("\"description\"");
    }

    @Test
    @DisplayName("substitutes a field-bound param inside percentile_cont's ordering column")
    void substitutesParamInsidePercentile() {
        StructuredQuery query = new StructuredQuery(
                "test_suites",
                null,
                QueryMode.AGGREGATE,
                false,
                List.of(new OutputColumn(
                        new FnExpr(
                                "percentile_cont",
                                false,
                                List.of(value(ValueType.DECIMAL, "0.5"), new ParamExpr("metricField"))),
                        "med")),
                null,
                null,
                null,
                new OffsetPage(0, 50, false));
        String sql = renderWithParams(query, Map.of("metricField", field("version")));
        assertThat(sql).contains("percentile_cont(").contains("within group").contains("\"version\"");
    }

    @Test
    @DisplayName("mean(:array) folds its elements into (e1+…+en)/n over an array-bound param")
    void rendersMeanOverArrayParam() {
        StructuredQuery query = new StructuredQuery(
                "test_suites",
                null,
                QueryMode.AGGREGATE,
                false,
                List.of(new OutputColumn(new FnExpr("mean", false, List.of(new ParamExpr("metricAvgs"))), "value")),
                null,
                null,
                null,
                new OffsetPage(0, 50, false));
        ArrayExpr metricAvgs = new ArrayExpr(List.of(
                new FnExpr("avg", false, List.of(field("version"))),
                new FnExpr("avg", false, List.of(field("created_at_ms")))));
        String sql = renderWithParams(query, Map.of("metricAvgs", metricAvgs));
        assertThat(sql)
                .contains("avg(\"meta\".\"test_suites\".\"version\")")
                .contains("avg(\"meta\".\"test_suites\".\"created_at_ms\")")
                .contains("/");
    }

    @Test
    @DisplayName("rejects mean when its argument does not resolve to an array")
    void rejectsMeanWithNonArrayArgument() {
        StructuredQuery query = new StructuredQuery(
                "test_suites",
                null,
                QueryMode.AGGREGATE,
                false,
                List.of(new OutputColumn(new FnExpr("mean", false, List.of(new ParamExpr("p"))), "value")),
                null,
                null,
                null,
                new OffsetPage(0, 50, false));
        StructuredQuery resolved = parameterResolver.resolve(query, Map.of("p", value(ValueType.DECIMAL, "0.5")));
        assertThatThrownBy(() -> builder.build(dsl, TEST_SUITES, bindings, resolved))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("array");
    }

    private String renderWithParams(StructuredQuery query, Map<String, Expr> params) {
        return dsl.renderInlined(builder.build(dsl, TEST_SUITES, bindings, parameterResolver.resolve(query, params)))
                .toLowerCase(Locale.ROOT);
    }
}
