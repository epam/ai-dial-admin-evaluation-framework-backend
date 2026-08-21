package com.epam.aidial.evaluation.query.service.translate;

import com.epam.aidial.evaluation.query.model.ArrayExpr;
import com.epam.aidial.evaluation.query.model.Expr;
import com.epam.aidial.evaluation.query.model.FieldExpr;
import com.epam.aidial.evaluation.query.model.FnExpr;
import com.epam.aidial.evaluation.query.model.ParamExpr;
import com.epam.aidial.evaluation.query.model.SubqueryExpr;
import com.epam.aidial.evaluation.query.model.ValueExpr;
import com.epam.aidial.evaluation.query.service.QueryFieldBinding;
import com.epam.aidial.evaluation.query.service.translate.function.FunctionContext;
import com.epam.aidial.evaluation.query.service.translate.function.QueryFunctionRegistry;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jooq.Field;
import org.jooq.Record1;
import org.jooq.Select;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Translates a single {@link Expr} into a jOOQ {@link Field} for use in projections, comparison
 * operands, and aggregate arguments:
 *
 * <ul>
 *   <li>{@link FieldExpr} → the bound generated column (unknown names are rejected — the field
 *       allowlist is the schema the client discovered);
 *   <li>{@link ValueExpr} → a typed bind parameter via {@link ValueExprToObjectMapper};
 *   <li>{@link FnExpr} → dispatched to a {@link QueryFunctionRegistry} bean (pluggable catalog);
 *   <li>{@link ParamExpr} → rejected here: {@code param} expressions are substituted away by
 *       {@code QueryParameterResolver} before translation, so a surviving one means it was unbound
 *       (e.g. submitted to the paramless public execute endpoint);
 *   <li>{@link ArrayExpr} → rejected here (only meaningful as the right operand of {@code in},
 *       handled by {@link FilterTranslator}).
 * </ul>
 *
 * <p>This translator is parameter-agnostic; parameter binding is resolved in a single pre-pass by
 * {@code QueryParameterResolver}, not threaded through translation.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class ExprTranslator {

    private final ValueExprToObjectMapper valueExprToObjectMapper;
    private final JsonbFieldResolver jsonbFieldResolver;
    private final QueryFunctionRegistry functionRegistry;

    /** Lazy so constructing this bean never requires {@link StructuredQueryBuilder} to exist yet
     *  (breaks the {@code StructuredQueryBuilder → FilterTranslator/ExprTranslator} constructor cycle);
     *  the only such reference in the translation pipeline. */
    private final ObjectProvider<StructuredQueryBuilder> queryBuilderProvider;

    /** Resolves a {@link FieldExpr} to its bound column, rejecting unknown field names. */
    public Field<?> resolveField(FieldExpr expr, Map<String, QueryFieldBinding> bindings) {
        return resolveField(expr.name(), bindings);
    }

    /**
     * Resolves a field name to its jOOQ {@link Field}: a base column from the bindings, or — for a
     * complex entity's published flattened name ({@code data::}/{@code response::}/{@code metric::}/
     * {@code metricInfo::}) — the JSONB path it denotes. Unknown names are rejected (the field
     * allowlist is the schema the client discovered).
     */
    public Field<?> resolveField(String name, Map<String, QueryFieldBinding> bindings) {
        final Field<?> field = resolveFieldOrNull(name, bindings);
        if (field == null) {
            throw new ValidationException("unknown field '" + name + "'");
        }
        return field;
    }

    /**
     * Like {@link #resolveField(String, Map)} but returns {@code null} for an unknown name instead of
     * throwing, so callers (e.g. group-key resolution) can fall back to other sources before failing.
     */
    public Field<?> resolveFieldOrNull(String name, Map<String, QueryFieldBinding> bindings) {
        final QueryFieldBinding binding = bindings.get(name);
        if (binding != null) {
            return binding.field();
        }
        return jsonbFieldResolver.resolve(name, bindings);
    }

    /** Translates any non-array expression into a jOOQ {@link Field}. */
    public Field<?> toField(Expr expr, Map<String, QueryFieldBinding> bindings) {
        if (expr == null) {
            throw new ValidationException("missing expression; a select/filter entry has no 'expr'");
        }
        return switch (expr) {
            case FieldExpr field -> resolveField(field, bindings);
            case ValueExpr value -> DSL.val(valueExprToObjectMapper.map(value));
            case FnExpr fn -> functionRegistry.translate(fn, new FunctionContext(this, bindings));
            case ParamExpr param -> throw new ValidationException("unbound query parameter '" + param.name() + "'");
            case ArrayExpr _ ->
                throw new ValidationException("array expressions are only valid as the right operand of 'in'");
            case SubqueryExpr subquery -> subqueryField(compileSubqueryMembership(subquery));
        };
    }

    /**
     * Delegates to {@link StructuredQueryBuilder#compileSubqueryMembership}, resolved lazily via
     * {@link #queryBuilderProvider}. Used both for a scalar expression position (wrapped as a
     * {@link Field} via {@link #subqueryField}) and, via {@link FilterTranslator}, for {@code in}'s
     * right operand (used directly as a {@link Select}).
     */
    Select<? extends Record1<?>> compileSubqueryMembership(SubqueryExpr subquery) {
        return queryBuilderProvider.getObject().compileSubqueryMembership(subquery);
    }

    /** Wraps a compiled subquery select as a scalar {@link Field}; jOOQ's generic overload can't infer
     *  its type parameter from a captured wildcard, so the cast is isolated here. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Field<?> subqueryField(Select<? extends Record1<?>> select) {
        return DSL.field((Select) select);
    }
}
