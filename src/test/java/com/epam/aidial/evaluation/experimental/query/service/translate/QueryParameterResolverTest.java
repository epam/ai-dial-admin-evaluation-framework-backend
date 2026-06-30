package com.epam.aidial.evaluation.experimental.query.service.translate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.epam.aidial.evaluation.experimental.query.model.ArrayExpr;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonNode;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonOp;
import com.epam.aidial.evaluation.experimental.query.model.Expr;
import com.epam.aidial.evaluation.experimental.query.model.FieldExpr;
import com.epam.aidial.evaluation.experimental.query.model.FilterNode;
import com.epam.aidial.evaluation.experimental.query.model.FnExpr;
import com.epam.aidial.evaluation.experimental.query.model.OffsetPage;
import com.epam.aidial.evaluation.experimental.query.model.OutputColumn;
import com.epam.aidial.evaluation.experimental.query.model.ParamExpr;
import com.epam.aidial.evaluation.experimental.query.model.QueryMode;
import com.epam.aidial.evaluation.experimental.query.model.StructuredQuery;
import com.epam.aidial.evaluation.experimental.query.model.ValueExpr;
import com.epam.aidial.evaluation.experimental.query.model.ValueType;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the parameter pre-pass: a {@link StructuredQuery} is rewritten into a param-free copy
 * by substituting every {@code param} with its bound expression (recursively), before translation.
 */
class QueryParameterResolverTest {

    private final QueryParameterResolver resolver = new QueryParameterResolver();

    private static StructuredQuery selecting(Expr expr) {
        return new StructuredQuery(
                "eval_summaries",
                null,
                QueryMode.AGGREGATE,
                false,
                List.of(new OutputColumn(expr, "value")),
                null,
                null,
                null,
                new OffsetPage(0, 50, false));
    }

    private static StructuredQuery filtering(FilterNode filter) {
        return new StructuredQuery(
                "eval_summaries", filter, QueryMode.ROW, false, null, null, null, null, new OffsetPage(0, 50, false));
    }

    private static Expr selectExpr(StructuredQuery query) {
        return query.select().getFirst().expr();
    }

    @Test
    @DisplayName("returns the same query instance when the binding map is empty")
    void emptyMapIsIdentity() {
        StructuredQuery query = selecting(new FnExpr("avg", false, List.of(new ParamExpr("metricField"))));
        assertThat(resolver.resolve(query, Map.of())).isSameAs(query);
    }

    @Test
    @DisplayName("substitutes a value-bound param with the bound value expression")
    void substitutesValueParam() {
        StructuredQuery query =
                filtering(new ComparisonNode(ComparisonOp.EQ, List.of(new FieldExpr("name"), new ParamExpr("p"))));
        StructuredQuery resolved = resolver.resolve(query, Map.of("p", new ValueExpr(ValueType.STRING, "demo")));

        ComparisonNode cmp = (ComparisonNode) resolved.filter();
        assertThat(cmp.args().get(1)).isInstanceOf(ValueExpr.class);
        assertThat(((ValueExpr) cmp.args().get(1)).value()).isEqualTo("demo");
    }

    @Test
    @DisplayName("substitutes a field-bound param nested inside a function argument")
    void substitutesFieldParamInsideFunction() {
        StructuredQuery query = selecting(new FnExpr("avg", false, List.of(new ParamExpr("metricField"))));
        StructuredQuery resolved =
                resolver.resolve(query, Map.of("metricField", new FieldExpr("metric::Relevancy::score")));

        FnExpr fn = (FnExpr) selectExpr(resolved);
        assertThat(fn.args().getFirst()).isInstanceOf(FieldExpr.class);
        assertThat(((FieldExpr) fn.args().getFirst()).name()).isEqualTo("metric::Relevancy::score");
    }

    @Test
    @DisplayName("substitutes an array-bound param (e.g. mean's argument) preserving its items")
    void substitutesArrayParam() {
        StructuredQuery query = selecting(new FnExpr("mean", false, List.of(new ParamExpr("metricAvgs"))));
        ArrayExpr bound = new ArrayExpr(List.of(
                new FnExpr("avg", false, List.of(new FieldExpr("metric::A::score"))),
                new FnExpr("avg", false, List.of(new FieldExpr("metric::B::score")))));

        StructuredQuery resolved = resolver.resolve(query, Map.of("metricAvgs", bound));

        FnExpr mean = (FnExpr) selectExpr(resolved);
        assertThat(mean.args().getFirst()).isInstanceOf(ArrayExpr.class);
        assertThat(((ArrayExpr) mean.args().getFirst()).items()).hasSize(2);
    }

    @Test
    @DisplayName("rejects an unbound param when a (non-empty) binding map lacks its name")
    void rejectsUnboundParam() {
        StructuredQuery query =
                filtering(new ComparisonNode(ComparisonOp.EQ, List.of(new FieldExpr("name"), new ParamExpr("p"))));
        assertThatThrownBy(() -> resolver.resolve(query, Map.of("other", new ValueExpr(ValueType.STRING, "x"))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("unbound query parameter 'p'");
    }

    @Test
    @DisplayName("rejects a param bound directly to another param")
    void rejectsParamBoundToParam() {
        StructuredQuery query =
                filtering(new ComparisonNode(ComparisonOp.EQ, List.of(new FieldExpr("name"), new ParamExpr("p"))));
        assertThatThrownBy(() -> resolver.resolve(query, Map.of("p", new ParamExpr("q"))))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("must not be bound to another parameter");
    }

    @Test
    @DisplayName("rejects a cyclic binding chain reached through nested expressions")
    void rejectsCyclicChain() {
        StructuredQuery query = selecting(new ParamExpr("a"));
        Map<String, Expr> params = Map.of(
                "a", new FnExpr("avg", false, List.of(new ParamExpr("b"))),
                "b", new FnExpr("avg", false, List.of(new ParamExpr("a"))));
        assertThatThrownBy(() -> resolver.resolve(query, params))
                .isInstanceOf(ValidationException.class)
                .hasMessageContaining("cyclic");
    }
}
