package com.epam.aidial.evaluation.experimental.query.service.translate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.epam.aidial.evaluation.data.db.repository.sql.json.PostgresJsonPathAccessor;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonNode;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonOp;
import com.epam.aidial.evaluation.experimental.query.model.Expr;
import com.epam.aidial.evaluation.experimental.query.model.FieldExpr;
import com.epam.aidial.evaluation.experimental.query.model.FilterNode;
import com.epam.aidial.evaluation.experimental.query.model.FnExpr;
import com.epam.aidial.evaluation.experimental.query.model.LogicalNode;
import com.epam.aidial.evaluation.experimental.query.model.LogicalOp;
import com.epam.aidial.evaluation.experimental.query.model.ValueExpr;
import com.epam.aidial.evaluation.experimental.query.model.ValueType;
import com.epam.aidial.evaluation.experimental.query.service.QueryFieldBinding;
import com.epam.aidial.evaluation.experimental.query.service.dto.QueryFieldType;
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
 * Renders the SQL produced by {@link FilterTranslator} for the null-polarity contract, without a database:
 * negated operators ({@code nc}, {@code ne} with a non-null operand) and the {@code not} node are total, so
 * a null operand satisfies them; positive operators keep SQL three-valued semantics and stay free of a
 * {@code BooleanTest} wrapper so they remain sargable. Covers GH #141, where {@code nc} over a null per-turn
 * field rendered UNKNOWN and excluded the test case from run selection.
 */
class FilterTranslatorNullSemanticsTest {

    private static final String NULL_SATISFIES = "is not false";
    private static final String NULL_NEGATES = "is not true";

    private final DSLContext dsl = DSL.using(SQLDialect.POSTGRES);
    private final PostgresJsonPathAccessor jsonPathAccessor = new PostgresJsonPathAccessor();

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

    /** "data::expected" is a scalar text field (the GH #141 shape); "data::tags" is array-typed. */
    private final Map<String, QueryFieldBinding> bindings = Map.of(
            "data::expected",
                    new QueryFieldBinding(
                            "data::expected",
                            jsonPathAccessor.jsonbAtAsText(dataColumn, DSL.val("expected")),
                            QueryFieldType.STRING),
            "data::tags",
                    new QueryFieldBinding(
                            "data::tags", jsonPathAccessor.jsonbAt(dataColumn, DSL.val("tags")), QueryFieldType.ARRAY),
            "name",
                    new QueryFieldBinding(
                            "name", DSL.field(DSL.name("name"), SQLDataType.VARCHAR), QueryFieldType.STRING));

    private String render(FilterNode node) {
        return dsl.renderInlined(filterTranslator.toCondition(node, bindings)).toLowerCase(Locale.ROOT);
    }

    private static ComparisonNode cmp(ComparisonOp op, Expr left, Expr right) {
        return new ComparisonNode(op, List.of(left, right));
    }

    private static ValueExpr string(String value) {
        return new ValueExpr(ValueType.STRING, value);
    }

    @Test
    @DisplayName("'nc' on a scalar field is total: a null value satisfies NOT CONTAIN")
    void scalarNotContainsIsNullSatisfying() {
        String sql = render(cmp(ComparisonOp.NC, new FieldExpr("data::expected"), string("London")));
        assertThat(sql).contains(NULL_SATISFIES).contains("not ilike").contains("%london%");
    }

    @Test
    @DisplayName("'nc' on an array field is total: a null array satisfies NOT CONTAIN")
    void arrayNotContainsIsNullSatisfying() {
        String sql = render(cmp(ComparisonOp.NC, new FieldExpr("data::tags"), string("text")));
        assertThat(sql).contains(NULL_SATISFIES).contains("not").doesNotContain("like");
    }

    @Test
    @DisplayName("'ne' with a non-null operand is total: a null value satisfies NOT EQUALS")
    void notEqualsIsNullSatisfying() {
        String sql = render(cmp(ComparisonOp.NE, new FieldExpr("data::expected"), string("London")));
        assertThat(sql).contains(NULL_SATISFIES).contains("<>");
    }

    @Test
    @DisplayName("'nc' with a function-wrapped left operand is total without duplicating the expression")
    void functionWrappedNotContainsIsNullSatisfying() {
        FnExpr lowerName = new FnExpr("lower", false, List.of(new FieldExpr("name")));
        String sql = render(cmp(ComparisonOp.NC, lowerName, string("abc")));
        assertThat(sql).contains(NULL_SATISFIES).contains("not ilike");
        assertThat(sql.split("lower\\(", -1)).hasSize(2);
    }

    @Test
    @DisplayName("'not' over a positive predicate is total: a null value satisfies the negation")
    void notNodeIsNullSatisfying() {
        LogicalNode not = new LogicalNode(
                LogicalOp.NOT, List.of(cmp(ComparisonOp.CO, new FieldExpr("data::expected"), string("London"))));
        assertThat(render(not)).contains(NULL_NEGATES);
    }

    @Test
    @DisplayName("'eq'/'ne' against a null literal keep their IS NULL / IS NOT NULL translation")
    void nullLiteralComparisonsUnchanged() {
        String isNull =
                render(cmp(ComparisonOp.EQ, new FieldExpr("data::expected"), new ValueExpr(ValueType.NULL, null)));
        String isNotNull =
                render(cmp(ComparisonOp.NE, new FieldExpr("data::expected"), new ValueExpr(ValueType.NULL, null)));
        assertThat(isNull).contains("is null").doesNotContain(NULL_SATISFIES);
        assertThat(isNotNull).contains("is not null").doesNotContain(NULL_SATISFIES);
    }

    @Test
    @DisplayName("Positive operators render unwrapped so they stay sargable")
    void positiveOperatorsRenderUnwrapped() {
        assertThat(render(cmp(ComparisonOp.CO, new FieldExpr("data::expected"), string("London"))))
                .doesNotContain(NULL_SATISFIES)
                .doesNotContain(NULL_NEGATES);
        assertThat(render(cmp(ComparisonOp.EQ, new FieldExpr("name"), string("suite-a"))))
                .doesNotContain(NULL_SATISFIES)
                .doesNotContain(NULL_NEGATES);
        assertThat(render(cmp(ComparisonOp.CO, new FieldExpr("data::tags"), string("text"))))
                .doesNotContain(NULL_SATISFIES)
                .doesNotContain(NULL_NEGATES);
    }

    @Test
    @DisplayName("A negated leaf stays total inside an 'and' conjunction")
    void negatedLeafStaysTotalUnderConjunction() {
        LogicalNode and = new LogicalNode(
                LogicalOp.AND,
                List.of(
                        cmp(ComparisonOp.EQ, new FieldExpr("name"), string("suite-a")),
                        cmp(ComparisonOp.NC, new FieldExpr("data::expected"), string("London"))));
        assertThat(render(and)).contains(NULL_SATISFIES).contains("and");
    }
}
