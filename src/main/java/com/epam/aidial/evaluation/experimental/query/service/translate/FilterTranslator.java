package com.epam.aidial.evaluation.experimental.query.service.translate;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.experimental.query.model.ArrayExpr;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonNode;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonOp;
import com.epam.aidial.evaluation.experimental.query.model.Expr;
import com.epam.aidial.evaluation.experimental.query.model.FilterNode;
import com.epam.aidial.evaluation.experimental.query.model.LogicalNode;
import com.epam.aidial.evaluation.experimental.query.model.ValueExpr;
import com.epam.aidial.evaluation.experimental.query.model.ValueType;
import com.epam.aidial.evaluation.experimental.query.service.QueryFieldBinding;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

/**
 * Translates the recursive filter tree (§3) into a jOOQ {@link Condition}. Logical nodes map to
 * {@code AND}/{@code OR}/{@code NOT}; comparison nodes are treated as binary {@code left op right}
 * where the left operand is any translatable expression and the right is a literal, field, or — for
 * {@code in} — an array of literals. A {@code null} tree means "no filter" ({@code TRUE}).
 *
 * <p>Used for both {@code filter} (against base-field bindings) and {@code having} (against bindings
 * augmented with aggregate aliases) — the binding map supplied by the caller decides which names are
 * resolvable.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class FilterTranslator {

    /** Escape character for {@code co}/{@code nc} LIKE patterns so operand metacharacters match literally. */
    private static final char LIKE_ESCAPE = '\\';

    private final ExprTranslator exprTranslator;

    /** Translates a filter tree with no parameter bindings. */
    public Condition toCondition(FilterNode node, Map<String, QueryFieldBinding> bindings) {
        return toCondition(node, bindings, Map.of());
    }

    /** Translates a filter tree, resolving {@link com.epam.aidial.evaluation.experimental.query.model.ParamExpr}
     * operands against {@code params}. */
    public Condition toCondition(FilterNode node, Map<String, QueryFieldBinding> bindings, Map<String, Expr> params) {
        if (node == null) {
            return DSL.trueCondition();
        }
        return switch (node) {
            case LogicalNode logical -> toLogical(logical, bindings, params);
            case ComparisonNode comparison -> toComparison(comparison, bindings, params);
        };
    }

    private Condition toLogical(LogicalNode node, Map<String, QueryFieldBinding> bindings, Map<String, Expr> params) {
        final List<FilterNode> args = node.args() == null ? List.of() : node.args();
        return switch (node.op()) {
            case AND -> DSL.and(translateAll(args, bindings, params));
            case OR -> DSL.or(translateAll(args, bindings, params));
            case NOT -> {
                if (args.size() != 1) {
                    throw new ValidationException("'not' expects exactly one child node");
                }
                yield DSL.not(toCondition(args.get(0), bindings, params));
            }
        };
    }

    private List<Condition> translateAll(
            List<FilterNode> nodes, Map<String, QueryFieldBinding> bindings, Map<String, Expr> params) {
        if (nodes.isEmpty()) {
            throw new ValidationException("'and'/'or' require at least one child node");
        }
        final List<Condition> conditions = new ArrayList<>(nodes.size());
        for (final FilterNode child : nodes) {
            conditions.add(toCondition(child, bindings, params));
        }
        return conditions;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Condition toComparison(
            ComparisonNode node, Map<String, QueryFieldBinding> bindings, Map<String, Expr> params) {
        final List<Expr> args = node.args() == null ? List.of() : node.args();
        if (args.size() != 2) {
            throw new ValidationException("comparison '" + node.op().code() + "' expects exactly two arguments");
        }
        final Field left = exprTranslator.toField(args.get(0), bindings, params);
        final Expr right = args.get(1);
        final ComparisonOp op = node.op();

        if (op == ComparisonOp.IN) {
            return left.in(inValues(right, bindings, params));
        }
        if (isNullLiteral(right)) {
            return switch (op) {
                case EQ -> left.isNull();
                case NE -> left.isNotNull();
                default ->
                    throw new ValidationException("null literal is only valid with 'eq'/'ne', not '" + op.code() + "'");
            };
        }
        return switch (op) {
            case CO -> ((Field<String>) left).likeIgnoreCase(containsPattern(right), LIKE_ESCAPE);
            case NC -> ((Field<String>) left).notLikeIgnoreCase(containsPattern(right), LIKE_ESCAPE);
            case EQ -> left.eq(exprTranslator.toField(right, bindings, params));
            case NE -> left.ne(exprTranslator.toField(right, bindings, params));
            case LT -> left.lt(exprTranslator.toField(right, bindings, params));
            case GT -> left.gt(exprTranslator.toField(right, bindings, params));
            case LE -> left.le(exprTranslator.toField(right, bindings, params));
            case GE -> left.ge(exprTranslator.toField(right, bindings, params));
            case IN -> throw new IllegalStateException("'in' handled above");
        };
    }

    private List<Object> inValues(Expr right, Map<String, QueryFieldBinding> bindings, Map<String, Expr> params) {
        if (!(right instanceof ArrayExpr array)) {
            throw new ValidationException("'in' requires an array as its right operand");
        }
        final List<Expr> items = array.items() == null ? List.of() : array.items();
        if (items.isEmpty()) {
            throw new ValidationException("'in' requires a non-empty array");
        }
        final List<Object> values = new ArrayList<>(items.size());
        for (final Expr item : items) {
            if (!(item instanceof ValueExpr value)) {
                throw new ValidationException("'in' array items must be literal values");
            }
            values.add(exprTranslator.toField(value, bindings, params));
        }
        return values;
    }

    private static boolean isNullLiteral(Expr expr) {
        return expr instanceof ValueExpr value && value.valueType() == ValueType.NULL;
    }

    /**
     * Builds the {@code %…%} LIKE pattern for a {@code co}/{@code nc} operand, escaping the LIKE
     * metacharacters ({@code %}, {@code _}, and the escape character itself) so the operand matches
     * literally rather than as a wildcard pattern.
     */
    private static String containsPattern(Expr right) {
        if (right instanceof ValueExpr value && value.valueType() == ValueType.STRING && value.value() != null) {
            return "%" + escapeLike(value.value()) + "%";
        }
        throw new ValidationException("'co'/'nc' require a string literal right operand");
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }
}
