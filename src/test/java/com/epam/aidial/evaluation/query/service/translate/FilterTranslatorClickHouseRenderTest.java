package com.epam.aidial.evaluation.query.service.translate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.epam.aidial.evaluation.data.db.repository.sql.json.DialectAwareJsonPathAccessor;
import com.epam.aidial.evaluation.query.model.ComparisonNode;
import com.epam.aidial.evaluation.query.model.ComparisonOp;
import com.epam.aidial.evaluation.query.model.Expr;
import com.epam.aidial.evaluation.query.model.FieldExpr;
import com.epam.aidial.evaluation.query.model.FilterNode;
import com.epam.aidial.evaluation.query.model.LogicalNode;
import com.epam.aidial.evaluation.query.model.LogicalOp;
import com.epam.aidial.evaluation.query.model.ValueExpr;
import com.epam.aidial.evaluation.query.model.ValueType;
import com.epam.aidial.evaluation.query.service.QueryFieldBinding;
import com.epam.aidial.evaluation.query.service.dto.QueryFieldType;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

/**
 * Render-pinning test for {@link FilterTranslator}'s four ClickHouse branches (null-satisfies,
 * negate, and the two {@code co}/{@code nc} array-containment forms). The existing
 * {@code FilterTranslatorNullSemanticsTest}/{@code FilterTranslatorArrayContainmentTest} already pin
 * the Postgres/default-family rendering unchanged; this class adds the ClickHouse coverage plus one
 * default-family guard per seam confirming the ClickHouse-only tokens never leak into it.
 */
class FilterTranslatorClickHouseRenderTest {

    private final DialectAwareJsonPathAccessor jsonPathAccessor = new DialectAwareJsonPathAccessor();

    private final ValueExprToObjectMapper valueExprToObjectMapper = new ValueExprToObjectMapper();
    private final JsonbFieldResolver jsonbFieldResolver = new JsonbFieldResolver(jsonPathAccessor);

    @SuppressWarnings("unchecked")
    private final ObjectProvider<StructuredQueryBuilder> queryBuilderProvider = mock(ObjectProvider.class);

    private final ExprTranslator exprTranslator = new ExprTranslator(
            valueExprToObjectMapper,
            jsonbFieldResolver,
            QueryFunctionTestSupport.registry(valueExprToObjectMapper),
            queryBuilderProvider);

    private final FilterTranslator filterTranslator = new FilterTranslator(exprTranslator);

    private final Field<JSONB> dataColumn = DSL.field(DSL.name("data"), SQLDataType.JSONB);

    private final Map<String, QueryFieldBinding> bindings = Map.of(
            "data::expected",
                    new QueryFieldBinding(
                            "data::expected",
                            jsonPathAccessor.jsonbAtAsText(dataColumn, DSL.val("expected")),
                            QueryFieldType.STRING),
            "data::tags",
                    new QueryFieldBinding(
                            "data::tags", jsonPathAccessor.jsonbAt(dataColumn, DSL.val("tags")), QueryFieldType.ARRAY));

    private String render(SQLDialect dialect, FilterNode node) {
        final DSLContext dsl = DSL.using(dialect);
        return dsl.renderInlined(filterTranslator.toCondition(node, bindings)).toLowerCase(Locale.ROOT);
    }

    private static ComparisonNode cmp(ComparisonOp op, Expr left, Expr right) {
        return new ComparisonNode(op, List.of(left, right));
    }

    private static ValueExpr string(String value) {
        return new ValueExpr(ValueType.STRING, value);
    }

    @Test
    @DisplayName("'nc' (nullSatisfies) on ClickHouse renders ifNull(..., true) instead of IS NOT FALSE")
    void nullSatisfiesOnClickHouse() {
        String sql = render(SQLDialect.CLICKHOUSE, cmp(ComparisonOp.NE, new FieldExpr("data::expected"), string("x")));
        assertThat(sql).contains("ifnull((").contains("), true)").doesNotContain("is not false");
    }

    @Test
    @DisplayName("'nc' (nullSatisfies) on the default family keeps the IS NOT FALSE rendering")
    void nullSatisfiesOnDefaultFamilyUnchanged() {
        String sql = render(SQLDialect.DEFAULT, cmp(ComparisonOp.NE, new FieldExpr("data::expected"), string("x")));
        assertThat(sql).contains("is not false").doesNotContain("ifnull");
    }

    @Test
    @DisplayName("'not' (negate) on ClickHouse renders not(ifNull(..., false)) instead of IS NOT TRUE")
    void negateOnClickHouse() {
        FilterNode not = new LogicalNode(
                LogicalOp.NOT, List.of(cmp(ComparisonOp.CO, new FieldExpr("data::expected"), string("x"))));
        String sql = render(SQLDialect.CLICKHOUSE, not);
        assertThat(sql).contains("not(ifnull((").contains("), false))").doesNotContain("is not true");
    }

    @Test
    @DisplayName("'not' (negate) on the default family keeps the IS NOT TRUE rendering")
    void negateOnDefaultFamilyUnchanged() {
        FilterNode not = new LogicalNode(
                LogicalOp.NOT, List.of(cmp(ComparisonOp.CO, new FieldExpr("data::expected"), string("x"))));
        String sql = render(SQLDialect.DEFAULT, not);
        assertThat(sql).contains("is not true").doesNotContain("ifnull");
    }

    @Test
    @DisplayName("'co' on an array field with a string element renders ClickHouse has(JSONExtract(...))")
    void arrayContainsStringElementOnClickHouse() {
        String sql = render(SQLDialect.CLICKHOUSE, cmp(ComparisonOp.CO, new FieldExpr("data::tags"), string("text")));
        assertThat(sql)
                .contains("has(jsonextract(")
                .contains("array(nullable(string))")
                .contains("'text'")
                .doesNotContain("like")
                .doesNotContain("?");
    }

    @Test
    @DisplayName("'co' on an array field with a non-string element renders ClickHouse arrayExists(...)")
    void arrayContainsScalarElementOnClickHouse() {
        String sql = render(
                SQLDialect.CLICKHOUSE,
                cmp(ComparisonOp.CO, new FieldExpr("data::tags"), new ValueExpr(ValueType.INTEGER, "5")));
        assertThat(sql)
                .contains("arrayexists(x -> jsonextract(x, 'nullable(float64)') = ")
                .contains("jsonextractarrayraw(")
                .doesNotContain("@>")
                .doesNotContain("to_jsonb");
    }

    @Test
    @DisplayName("'co' on an array field never takes the ClickHouse branch on the default family")
    void arrayContainsOnDefaultFamilyUnchanged() {
        String stringSql =
                render(SQLDialect.DEFAULT, cmp(ComparisonOp.CO, new FieldExpr("data::tags"), string("text")));
        assertThat(stringSql).doesNotContain("jsonextract").doesNotContain("has(");

        String scalarSql = render(
                SQLDialect.DEFAULT,
                cmp(ComparisonOp.CO, new FieldExpr("data::tags"), new ValueExpr(ValueType.INTEGER, "5")));
        assertThat(scalarSql).contains("@>").contains("to_jsonb").doesNotContain("arrayexists");
    }
}
