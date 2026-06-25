package com.epam.aidial.evaluation.experimental.query.service.translate;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.experimental.query.model.ArrayExpr;
import com.epam.aidial.evaluation.experimental.query.model.Expr;
import com.epam.aidial.evaluation.experimental.query.model.FieldExpr;
import com.epam.aidial.evaluation.experimental.query.model.FnExpr;
import com.epam.aidial.evaluation.experimental.query.model.ParamExpr;
import com.epam.aidial.evaluation.experimental.query.model.ValueExpr;
import com.epam.aidial.evaluation.experimental.query.service.QueryFieldBinding;
import com.epam.aidial.evaluation.experimental.query.service.translate.function.FunctionContext;
import com.epam.aidial.evaluation.experimental.query.service.translate.function.QueryFunctionRegistry;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jooq.Field;
import org.jooq.impl.DSL;
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
 *   <li>{@link ParamExpr} → substituted with the {@link Expr} bound to its name in the supplied
 *       params map, then translated recursively; an unbound name, or a binding to another
 *       {@link ParamExpr}, is rejected;
 *   <li>{@link ArrayExpr} → rejected here (only meaningful as the right operand of {@code in},
 *       handled by {@link FilterTranslator}, or unpacked by an array-aware function such as
 *       {@code mean}).
 * </ul>
 *
 * <p>Parameter bindings are supplied only by trusted internal callers; the public execute endpoint
 * passes an empty map, so a {@code param} in a public request is always unbound (rejected).
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class ExprTranslator {

    private final ValueExprToObjectMapper valueExprToObjectMapper;
    private final JsonbFieldResolver jsonbFieldResolver;
    private final QueryFunctionRegistry functionRegistry;

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

    /** Translates any non-array expression into a jOOQ {@link Field} with no parameter bindings. */
    public Field<?> toField(Expr expr, Map<String, QueryFieldBinding> bindings) {
        return toField(expr, bindings, Map.of());
    }

    /**
     * Translates any non-array expression into a jOOQ {@link Field}, resolving {@link ParamExpr}
     * nodes against {@code params} (parameter = expression substitution).
     */
    public Field<?> toField(Expr expr, Map<String, QueryFieldBinding> bindings, Map<String, Expr> params) {
        if (expr == null) {
            throw new ValidationException("missing expression; a select/filter entry has no 'expr'");
        }
        return switch (expr) {
            case FieldExpr field -> resolveField(field, bindings);
            case ValueExpr value -> DSL.val(valueExprToObjectMapper.map(value));
            case FnExpr fn -> functionRegistry.translate(fn, new FunctionContext(this, bindings, params));
            case ParamExpr param -> toField(substituteParam(param, params), bindings, params);
            case ArrayExpr ignored ->
                throw new ValidationException("array expressions are only valid as the right operand of 'in'");
        };
    }

    /**
     * One-level parameter substitution: a {@link ParamExpr} resolves to the {@link Expr} bound to its
     * name (any other expression is returned unchanged). An unbound name is rejected (HTTP 400);
     * binding a parameter to another parameter is rejected to keep substitution acyclic. Used both by
     * {@link #toField} and by array-aware functions that need the bound expression (not a field).
     */
    public Expr substituteParam(Expr expr, Map<String, Expr> params) {
        if (!(expr instanceof ParamExpr param)) {
            return expr;
        }
        final Expr bound = params.get(param.name());
        if (bound == null) {
            throw new ValidationException("unbound query parameter '" + param.name() + "'");
        }
        if (bound instanceof ParamExpr) {
            throw new ValidationException(
                    "query parameter '" + param.name() + "' must not be bound to another parameter");
        }
        return bound;
    }
}
