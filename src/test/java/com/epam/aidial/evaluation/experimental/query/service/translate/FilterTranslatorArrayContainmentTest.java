package com.epam.aidial.evaluation.experimental.query.service.translate;

import static org.assertj.core.api.Assertions.assertThat;

import com.epam.aidial.evaluation.data.db.repository.sql.json.PostgresJsonPathAccessor;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonNode;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonOp;
import com.epam.aidial.evaluation.experimental.query.model.Expr;
import com.epam.aidial.evaluation.experimental.query.model.FieldExpr;
import com.epam.aidial.evaluation.experimental.query.model.FilterNode;
import com.epam.aidial.evaluation.experimental.query.model.FnExpr;
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

/**
 * Renders the SQL produced by {@link FilterTranslator} for {@code co}/{@code nc} on array-typed vs
 * scalar fields, without a database. An {@code ARRAY}-typed field becomes JSONB element containment;
 * a scalar or function-wrapped left operand keeps case-insensitive LIKE.
 */
class FilterTranslatorArrayContainmentTest {

    private final DSLContext dsl = DSL.using(SQLDialect.POSTGRES);
    private final PostgresJsonPathAccessor jsonPathAccessor = new PostgresJsonPathAccessor();

    private final ValueExprToObjectMapper valueExprToObjectMapper = new ValueExprToObjectMapper();
    private final JsonbFieldResolver jsonbFieldResolver = new JsonbFieldResolver(jsonPathAccessor);
    private final ExprTranslator exprTranslator = new ExprTranslator(
            valueExprToObjectMapper, jsonbFieldResolver, QueryFunctionTestSupport.registry(valueExprToObjectMapper));

    private final FilterTranslator filterTranslator = new FilterTranslator(exprTranslator);

    private final Field<JSONB> dataColumn = DSL.field(DSL.name("data"), SQLDataType.JSONB);

    /** "data::tags" is array-typed; "name" is a scalar string column. */
    private final Map<String, QueryFieldBinding> bindings = Map.of(
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

    @Test
    @DisplayName("'co' on an array field with a string element renders JSONB element existence, not LIKE")
    void arrayContainsStringElement() {
        String sql = render(cmp(ComparisonOp.CO, new FieldExpr("data::tags"), new ValueExpr(ValueType.STRING, "text")));
        assertThat(sql).contains("?").contains("'text'").doesNotContain("like");
    }

    @Test
    @DisplayName("'nc' on an array field negates the JSONB element existence")
    void arrayNotContainsStringElement() {
        String sql = render(cmp(ComparisonOp.NC, new FieldExpr("data::tags"), new ValueExpr(ValueType.STRING, "text")));
        assertThat(sql).contains("not").contains("?").contains("'text'").doesNotContain("like");
    }

    @Test
    @DisplayName("'co' on an array field with a non-string element renders @> to_jsonb containment")
    void arrayContainsNonStringElement() {
        String sql = render(cmp(ComparisonOp.CO, new FieldExpr("data::tags"), new ValueExpr(ValueType.INTEGER, "5")));
        assertThat(sql).contains("@>").contains("to_jsonb").doesNotContain("like");
    }

    @Test
    @DisplayName("'co' on a scalar (non-array) field still renders case-insensitive LIKE")
    void scalarContainsFallsThroughToLike() {
        String sql = render(cmp(ComparisonOp.CO, new FieldExpr("name"), new ValueExpr(ValueType.STRING, "abc")));
        assertThat(sql).contains("like").contains("%abc%").doesNotContain("@>");
    }

    @Test
    @DisplayName("'co' with a function-wrapped (non-FieldExpr) left operand falls through to LIKE")
    void functionLeftFallsThroughToLike() {
        FnExpr lowerName = new FnExpr("lower", false, List.of(new FieldExpr("name")));
        String sql = render(cmp(ComparisonOp.CO, lowerName, new ValueExpr(ValueType.STRING, "abc")));
        assertThat(sql).contains("like").contains("lower(").doesNotContain("@>");
    }
}
