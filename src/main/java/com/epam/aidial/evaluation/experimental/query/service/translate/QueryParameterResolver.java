package com.epam.aidial.evaluation.experimental.query.service.translate;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.experimental.query.model.ArrayExpr;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonNode;
import com.epam.aidial.evaluation.experimental.query.model.Expr;
import com.epam.aidial.evaluation.experimental.query.model.FieldExpr;
import com.epam.aidial.evaluation.experimental.query.model.FilterNode;
import com.epam.aidial.evaluation.experimental.query.model.FnExpr;
import com.epam.aidial.evaluation.experimental.query.model.LogicalNode;
import com.epam.aidial.evaluation.experimental.query.model.OutputColumn;
import com.epam.aidial.evaluation.experimental.query.model.ParamExpr;
import com.epam.aidial.evaluation.experimental.query.model.StructuredQuery;
import com.epam.aidial.evaluation.experimental.query.model.ValueExpr;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.springframework.stereotype.Component;

/**
 * Resolves {@code param} expressions in a {@link StructuredQuery} <em>before</em> translation, by
 * rewriting the query into a param-free copy: every {@link ParamExpr} is replaced with the
 * {@link Expr} bound to its name (recursively, so params nested inside a bound expression are also
 * resolved). This keeps parameter handling in one place — the translator, builder, executor, and
 * repository stay completely param-agnostic.
 *
 * <p>Substitution is purely structural (no field bindings or SQL context needed), so it is a single
 * recursive pass over the only three places a {@link ParamExpr} can appear: {@code select}
 * expressions, {@code filter}, and {@code having}. ({@code sort}/{@code group_by}/{@code page} carry
 * no expressions.)
 *
 * <p>An unbound parameter, a parameter bound directly to another parameter, or a cyclic binding chain
 * is rejected with {@link ValidationException} (HTTP 400). An empty binding map is a no-op: the query
 * is returned unchanged and any surviving {@link ParamExpr} is rejected later by the translator — so
 * the public paramless execute endpoint still rejects {@code param} usage.
 */
@Component
@LogExecution
public class QueryParameterResolver {

    /** Rewrites {@code query} into a param-free copy using {@code params}; identity when empty. */
    public StructuredQuery resolve(StructuredQuery query, Map<String, Expr> params) {
        if (params == null || params.isEmpty()) {
            return query;
        }
        final List<OutputColumn> select = query.select() == null
                ? null
                : query.select().stream()
                        .map(col -> new OutputColumn(resolveExpr(col.expr(), params, new HashSet<>()), col.as()))
                        .toList();
        return new StructuredQuery(
                query.entity(),
                resolveFilter(query.filter(), params),
                query.mode(),
                query.distinct(),
                select,
                query.groupBy(),
                resolveFilter(query.having(), params),
                query.sort(),
                query.page());
    }

    private FilterNode resolveFilter(FilterNode node, Map<String, Expr> params) {
        return switch (node) {
            case null -> null;
            case LogicalNode(var op, var args) ->
                new LogicalNode(op, mapList(args, child -> resolveFilter(child, params)));
            case ComparisonNode(var op, var args) ->
                new ComparisonNode(op, mapList(args, arg -> resolveExpr(arg, params, new HashSet<>())));
        };
    }

    private Expr resolveExpr(Expr expr, Map<String, Expr> params, Set<String> resolving) {
        return switch (expr) {
            case null -> null; // a missing expression is reported by the translator, not here
            case FieldExpr field -> field;
            case ValueExpr value -> value;
            case ParamExpr(var name) -> resolveParam(name, params, resolving);
            case FnExpr(var name, var distinct, var args) ->
                new FnExpr(name, distinct, mapList(args, arg -> resolveExpr(arg, params, resolving)));
            case ArrayExpr(var items) -> new ArrayExpr(mapList(items, item -> resolveExpr(item, params, resolving)));
        };
    }

    private Expr resolveParam(String name, Map<String, Expr> params, Set<String> resolving) {
        if (!resolving.add(name)) {
            throw new ValidationException("query parameter '" + name + "' has a cyclic binding");
        }
        final Expr bound = params.get(name);
        if (bound == null) {
            throw new ValidationException("unbound query parameter '" + name + "'");
        }
        if (bound instanceof ParamExpr) {
            throw new ValidationException("query parameter '" + name + "' must not be bound to another parameter");
        }
        final Expr resolved = resolveExpr(bound, params, resolving);
        resolving.remove(name);
        return resolved;
    }

    private static <T> List<T> mapList(List<T> items, UnaryOperator<T> mapper) {
        return items == null ? null : items.stream().map(mapper).toList();
    }
}
