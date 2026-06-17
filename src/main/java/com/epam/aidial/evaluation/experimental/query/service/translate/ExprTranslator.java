package com.epam.aidial.evaluation.experimental.query.service.translate;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.experimental.query.model.ArrayExpr;
import com.epam.aidial.evaluation.experimental.query.model.Expr;
import com.epam.aidial.evaluation.experimental.query.model.FieldExpr;
import com.epam.aidial.evaluation.experimental.query.model.FnExpr;
import com.epam.aidial.evaluation.experimental.query.model.ParamExpr;
import com.epam.aidial.evaluation.experimental.query.model.ValueExpr;
import com.epam.aidial.evaluation.experimental.query.service.QueryFieldBinding;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

/**
 * Translates a single {@link Expr} into a jOOQ {@link Field} for use in projections, comparison
 * operands, and aggregate arguments. The grammar permits arbitrary expressions, but this demo
 * translator covers the common, safely-supported subset shared by all wired entities:
 *
 * <ul>
 *   <li>{@link FieldExpr} → the bound generated column (unknown names are rejected — the field
 *       allowlist is the schema the client discovered);
 *   <li>{@link ValueExpr} → a typed bind parameter via {@link ValueExprToObjectMapper};
 *   <li>{@link FnExpr} → a small allowlist of scalar SQL functions;
 *   <li>{@link ParamExpr} → rejected (no server-side param registry in the demo);
 *   <li>{@link ArrayExpr} → rejected here (only meaningful as the right operand of {@code in},
 *       handled by {@link FilterTranslator}).
 * </ul>
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class ExprTranslator {

    private final ValueExprToObjectMapper valueExprToObjectMapper;
    private final JsonbFieldResolver jsonbFieldResolver;

    /** Resolves a {@link FieldExpr} to its bound column, rejecting unknown field names. */
    public Field<?> resolveField(FieldExpr expr, Map<String, QueryFieldBinding> bindings) {
        return resolveField(expr.name(), bindings);
    }

    /**
     * Resolves a field name to its jOOQ {@link Field}: a base column from the bindings, or — for a
     * complex entity's published flattened name ({@code data:}/{@code response:}/{@code metric:}/
     * {@code metricInfo:}) — the JSONB path it denotes. Unknown names are rejected (the field
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
            case FnExpr fn -> toFunction(fn, bindings);
            case ParamExpr param ->
                throw new ValidationException(
                        "param expressions are not supported by the experimental query translator: '" + param.name()
                                + "'");
            case ArrayExpr ignored ->
                throw new ValidationException("array expressions are only valid as the right operand of 'in'");
        };
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Field<?> toFunction(FnExpr fn, Map<String, QueryFieldBinding> bindings) {
        final String name = fn.name() == null ? "" : fn.name().toLowerCase(Locale.ROOT);
        final List<Expr> args = fn.args() == null ? List.of() : fn.args();
        return switch (name) {
            case "lower" -> DSL.lower((Field<String>) requireSingleArg(name, args, bindings));
            case "upper" -> DSL.upper((Field<String>) requireSingleArg(name, args, bindings));
            case "length" -> DSL.length((Field<String>) requireSingleArg(name, args, bindings));
            case "trim" -> DSL.trim((Field<String>) requireSingleArg(name, args, bindings));
            case "abs" -> DSL.abs((Field) requireSingleArg(name, args, bindings));
            case "width_bucket" -> widthBucket(name, args, bindings);
            case "count" -> {
                if (args.isEmpty()) {
                    yield DSL.count();
                }
                final Field arg = requireSingleArg(name, args, bindings);
                yield fn.distinct() ? DSL.countDistinct(arg) : DSL.count(arg);
            }
            case "sum" -> {
                final Field arg = requireSingleArg(name, args, bindings);
                yield fn.distinct() ? DSL.sumDistinct(arg) : DSL.sum(arg);
            }
            case "avg" -> {
                final Field arg = requireSingleArg(name, args, bindings);
                yield fn.distinct() ? DSL.avgDistinct(arg) : DSL.avg(arg);
            }
            case "min" -> DSL.min((Field) requireSingleArg(name, args, bindings));
            case "max" -> DSL.max((Field) requireSingleArg(name, args, bindings));
            default -> throw new ValidationException("unsupported function '" + fn.name() + "' in query expression");
        };
    }

    private Field<?> requireSingleArg(String fn, List<Expr> args, Map<String, QueryFieldBinding> bindings) {
        if (args.size() != 1) {
            throw new ValidationException("function '" + fn + "' expects exactly one argument");
        }
        return toField(args.getFirst(), bindings);
    }

    /**
     * {@code width_bucket(operand, low, high, count)} — the histogram bucket (1..count) operand falls
     * into across {@code count} equal-width buckets spanning {@code [low, high)}; {@code 0} below
     * {@code low} and {@code count + 1} at or above {@code high}.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private Field<?> widthBucket(String fn, List<Expr> args, Map<String, QueryFieldBinding> bindings) {
        if (args.size() != 4) {
            throw new ValidationException(
                    "function '" + fn + "' expects exactly four arguments (operand, low, high, count)");
        }
        final Field operand = toField(args.get(0), bindings);
        final Field low = toField(args.get(1), bindings);
        final Field high = toField(args.get(2), bindings);
        final Field<Integer> count = toField(args.get(3), bindings).cast(Integer.class);
        return DSL.widthBucket(operand, low, high, count);
    }
}
