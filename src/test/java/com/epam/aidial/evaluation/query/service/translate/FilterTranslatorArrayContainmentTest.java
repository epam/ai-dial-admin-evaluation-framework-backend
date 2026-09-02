package com.epam.aidial.evaluation.query.service.translate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.epam.aidial.evaluation.data.db.repository.sql.json.DialectAwareJsonPathAccessor;
import com.epam.aidial.evaluation.query.model.ComparisonNode;
import com.epam.aidial.evaluation.query.model.ComparisonOp;
import com.epam.aidial.evaluation.query.model.Expr;
import com.epam.aidial.evaluation.query.model.FieldExpr;
import com.epam.aidial.evaluation.query.model.FilterNode;
import com.epam.aidial.evaluation.query.model.FnExpr;
import com.epam.aidial.evaluation.query.model.ValueExpr;
import com.epam.aidial.evaluation.query.model.ValueType;
import com.epam.aidial.evaluation.query.service.QueryFieldBinding;
import com.epam.aidial.evaluation.query.service.dto.QueryFieldType;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
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
 * Renders the SQL produced by {@link FilterTranslator} for {@code co}/{@code nc} on array-typed vs
 * scalar fields, without a database. A bare {@code ARRAY}-typed field becomes JSONB element
 * containment and a {@code lower}/{@code upper}-wrapped one becomes case-insensitive whole-element
 * containment; every other left operand keeps case-insensitive LIKE.
 */
class FilterTranslatorArrayContainmentTest {

    private final DSLContext dsl = DSL.using(SQLDialect.POSTGRES);
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
    @DisplayName("'co' with a lower()-wrapped scalar field falls through to LIKE")
    void functionLeftFallsThroughToLike() {
        FnExpr lowerName = new FnExpr("lower", false, List.of(new FieldExpr("name")));
        String sql = render(cmp(ComparisonOp.CO, lowerName, new ValueExpr(ValueType.STRING, "abc")));
        assertThat(sql).contains("like").contains("lower(").doesNotContain("@>");
    }

    @Test
    @DisplayName("'co' with a non-case-normalizing function over an array field falls through to LIKE unchanged")
    void nonCaseNormalizingFunctionOverArrayFallsThroughToLike() {
        FnExpr trimTags = new FnExpr("trim", false, List.of(new FieldExpr("data::tags")));
        String sql = render(cmp(ComparisonOp.CO, trimTags, new ValueExpr(ValueType.STRING, "abc")));
        assertThat(sql).contains("like").contains("trim(").doesNotContain("jsonb_array_elements_text");
    }

    @Test
    @DisplayName("'co' on a lower()-wrapped array field renders case-insensitive whole-element containment")
    void lowerWrappedArrayContainsIgnoresCase() {
        FnExpr lowerTags = new FnExpr("lower", false, List.of(new FieldExpr("data::tags")));
        String sql = render(cmp(ComparisonOp.CO, lowerTags, new ValueExpr(ValueType.STRING, "tee")));
        assertThat(sql)
                .contains("jsonb_array_elements_text")
                .contains("jsonb_typeof")
                .contains("lower(e.v)")
                .contains("'tee'")
                .doesNotContain("like");
        // The wrapper must be discarded, never applied to the JSONB path: the only case folding left in
        // the statement is the element/operand comparison the predicate itself introduces.
        assertThat(sql.replace("lower(e.v)", "").replace("lower('tee')", "")).doesNotContain("lower(");
    }

    @Test
    @DisplayName("'co' on an upper()-wrapped array field renders the same case-insensitive containment")
    void upperWrappedArrayContainsIgnoresCase() {
        FnExpr upperTags = new FnExpr("upper", false, List.of(new FieldExpr("data::tags")));
        String sql = render(cmp(ComparisonOp.CO, upperTags, new ValueExpr(ValueType.STRING, "tee")));
        assertThat(sql)
                .contains("jsonb_array_elements_text")
                .contains("lower(e.v)")
                .doesNotContain("like")
                // `upper` is consumed as a hint, so no upper() survives into the statement at all
                .doesNotContain("upper(");
    }

    @Test
    @DisplayName("'nc' on a lower()-wrapped array field negates the containment inside the 'is not false' wrapper")
    void lowerWrappedArrayNotContainsNegatesInsideNullSatisfies() {
        FnExpr lowerTags = new FnExpr("lower", false, List.of(new FieldExpr("data::tags")));
        String sql = render(cmp(ComparisonOp.NC, lowerTags, new ValueExpr(ValueType.STRING, "tee")));
        assertThat(sql)
                .contains("jsonb_array_elements_text")
                // the containment itself must be negated, not merely wrapped
                .contains("not (exists")
                .contains("is not false")
                .doesNotContain("like");
    }

    @Test
    @DisplayName("'co' on an array field wrapped in an upper-case LOWER routes to containment, not to lower(jsonb)")
    void wrapperNameIsMatchedIgnoringCase() {
        FnExpr lowerTags = new FnExpr("LOWER", false, List.of(new FieldExpr("data::tags")));
        String sql = render(cmp(ComparisonOp.CO, lowerTags, new ValueExpr(ValueType.STRING, "tee")));
        assertThat(sql).contains("jsonb_array_elements_text").doesNotContain("like");
        // The registry resolves function names case-insensitively, so an upper-case spelling must not fall
        // through to the literal translation that renders lower(jsonb) and fails at execution (GH #142).
        assertThat(sql.replace("lower(e.v)", "").replace("lower('tee')", "")).doesNotContain("lower(");
    }

    @Test
    @DisplayName("'co' on a lower()-wrapped array field with a null literal operand is rejected, not translated")
    void lowerWrappedArrayWithNullOperandRejected() {
        FnExpr lowerTags = new FnExpr("lower", false, List.of(new FieldExpr("data::tags")));
        FilterNode node = cmp(ComparisonOp.CO, lowerTags, new ValueExpr(ValueType.NULL, null));
        assertThatThrownBy(() -> filterTranslator.toCondition(node, bindings))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("null literal is only valid with 'eq'/'ne'");
    }

    @Test
    @DisplayName("'co' on a lower()-wrapped array field with a non-string operand drops the wrapper and keeps @>")
    void lowerWrappedArrayWithNonStringOperandKeepsJsonContainment() {
        FnExpr lowerTags = new FnExpr("lower", false, List.of(new FieldExpr("data::tags")));
        String sql = render(cmp(ComparisonOp.CO, lowerTags, new ValueExpr(ValueType.INTEGER, "5")));
        assertThat(sql)
                .contains("@>")
                .contains("to_jsonb")
                .doesNotContain("jsonb_array_elements_text")
                .doesNotContain("lower(");
    }
}
