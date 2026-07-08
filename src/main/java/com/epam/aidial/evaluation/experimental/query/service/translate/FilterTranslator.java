package com.epam.aidial.evaluation.experimental.query.service.translate;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.experimental.query.model.ArrayExpr;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonNode;
import com.epam.aidial.evaluation.experimental.query.model.ComparisonOp;
import com.epam.aidial.evaluation.experimental.query.model.Expr;
import com.epam.aidial.evaluation.experimental.query.model.FieldExpr;
import com.epam.aidial.evaluation.experimental.query.model.FilterNode;
import com.epam.aidial.evaluation.experimental.query.model.LogicalNode;
import com.epam.aidial.evaluation.experimental.query.model.StructuredQuery;
import com.epam.aidial.evaluation.experimental.query.model.SubqueryExpr;
import com.epam.aidial.evaluation.experimental.query.model.ValueExpr;
import com.epam.aidial.evaluation.experimental.query.model.ValueType;
import com.epam.aidial.evaluation.experimental.query.service.QueryFieldBinding;
import com.epam.aidial.evaluation.experimental.query.service.dto.QueryFieldType;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.Record1;
import org.jooq.Select;
import org.jooq.SelectQuery;
import org.jooq.Table;
import org.jooq.impl.DSL;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * Translates the recursive filter tree (§3) into a jOOQ {@link Condition}. Logical nodes map to
 * {@code AND}/{@code OR}/{@code NOT}; comparison nodes are treated as binary {@code left op right}
 * where the left operand is any translatable expression and the right is a literal, field, an array
 * (for {@code in}), or a {@code subquery} (for {@code in}). A {@code null} tree means "no filter"
 * ({@code TRUE}).
 *
 * <p>Used for both {@code filter} (against base-field bindings) and {@code having} (against bindings
 * augmented with aggregate aliases) — the binding map supplied by the caller decides which names are
 * resolvable.
 *
 * <p>A {@code subquery}-valued {@code in} is compiled to a nested {@code SELECT} ({@code left IN
 * (SELECT …)}). The enclosing {@link TranslationContext} (datasource, table, entity) needed to build
 * that nested select is passed to the {@code TranslationContext} overload and held for the duration of
 * the call in a {@link ThreadLocal} (mirroring {@code AuthorizationTokenHolder}), so the recursive walk
 * keeps its plain {@code (node, bindings)} signatures and only {@link #subquerySelect} reads it.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class FilterTranslator {

    /** Escape character for {@code co}/{@code nc} LIKE patterns so operand metacharacters match literally. */
    private static final char LIKE_ESCAPE = '\\';

    private final ExprTranslator exprTranslator;

    /**
     * Lazy reference to the query builder, used to compile a {@code subquery}-valued {@code in} operand
     * into a nested select. {@link ObjectProvider} breaks the {@code StructuredQueryBuilder →
     * FilterTranslator} constructor cycle (resolved on first use, not at construction).
     */
    private final ObjectProvider<StructuredQueryBuilder> queryBuilderProvider;

    /**
     * Enclosing context for the current translation, set only by the {@link TranslationContext} overload
     * and read only when compiling a {@code subquery}-valued {@code in}. {@code null} means "no subquery
     * support in this context" (the plain {@link #toCondition(FilterNode, Map)} entry).
     */
    private final ThreadLocal<TranslationContext> subqueryContext = new ThreadLocal<>();

    /**
     * Translates a filter tree into a jOOQ {@link Condition} ({@code null} tree → {@code TRUE}) in a
     * context that does not support {@code subquery}-valued {@code in} operands (a subquery operand is
     * rejected). Callers that need subqueries use the {@link TranslationContext} overload.
     */
    public Condition toCondition(FilterNode node, Map<String, QueryFieldBinding> bindings) {
        if (node == null) {
            return DSL.trueCondition();
        }
        return switch (node) {
            case LogicalNode logical -> toLogical(logical, bindings);
            case ComparisonNode comparison -> toComparison(comparison, bindings);
        };
    }

    /**
     * Translates a filter tree, making {@code ctx} available to compile a {@code subquery}-valued
     * {@code in}. The context is held in a {@link ThreadLocal} for the duration of the call and restored
     * afterwards, so nested/sibling subquery compilations see the correct enclosing context and nothing
     * leaks across calls.
     */
    public Condition toCondition(FilterNode node, Map<String, QueryFieldBinding> bindings, TranslationContext ctx) {
        final TranslationContext previous = subqueryContext.get();
        subqueryContext.set(ctx);
        try {
            return toCondition(node, bindings);
        } finally {
            if (previous == null) {
                subqueryContext.remove();
            } else {
                subqueryContext.set(previous);
            }
        }
    }

    private Condition toLogical(LogicalNode node, Map<String, QueryFieldBinding> bindings) {
        final List<FilterNode> args = node.args() == null ? List.of() : node.args();
        return switch (node.op()) {
            case AND -> DSL.and(translateAll(args, bindings));
            case OR -> DSL.or(translateAll(args, bindings));
            case NOT -> {
                if (args.size() != 1) {
                    throw new ValidationException("'not' expects exactly one child node");
                }
                yield DSL.not(toCondition(args.get(0), bindings));
            }
        };
    }

    private List<Condition> translateAll(List<FilterNode> nodes, Map<String, QueryFieldBinding> bindings) {
        if (nodes.isEmpty()) {
            throw new ValidationException("'and'/'or' require at least one child node");
        }
        final List<Condition> conditions = new ArrayList<>(nodes.size());
        for (final FilterNode child : nodes) {
            conditions.add(toCondition(child, bindings));
        }
        return conditions;
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private Condition toComparison(ComparisonNode node, Map<String, QueryFieldBinding> bindings) {
        final List<Expr> args = node.args() == null ? List.of() : node.args();
        if (args.size() != 2) {
            throw new ValidationException("comparison '" + node.op().code() + "' expects exactly two arguments");
        }
        final Expr leftExpr = args.get(0);
        final Expr right = args.get(1);
        final ComparisonOp op = node.op();

        // Array-element containment: `co`/`nc` on a bare array-typed field means "the JSONB array
        // contains this element", not substring LIKE. Precondition: the left operand is a bare
        // FieldExpr whose binding type is ARRAY (a function-wrapped or non-array left keeps LIKE).
        if ((op == ComparisonOp.CO || op == ComparisonOp.NC)
                && !isNullLiteral(right)
                && isArrayField(leftExpr, bindings)) {
            final Condition contains =
                    arrayContains((Field<JSONB>) exprTranslator.toField(leftExpr, bindings), right, bindings);
            return op == ComparisonOp.CO ? contains : DSL.not(contains);
        }

        final Field left = exprTranslator.toField(leftExpr, bindings);

        if (op == ComparisonOp.IN) {
            if (right instanceof SubqueryExpr subquery) {
                return left.in(subquerySelect(subquery, bindings));
            }
            return left.in(inValues(right, bindings));
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
            case EQ -> left.eq(exprTranslator.toField(right, bindings));
            case NE -> left.ne(exprTranslator.toField(right, bindings));
            case LT -> left.lt(exprTranslator.toField(right, bindings));
            case GT -> left.gt(exprTranslator.toField(right, bindings));
            case LE -> left.le(exprTranslator.toField(right, bindings));
            case GE -> left.ge(exprTranslator.toField(right, bindings));
            case IN -> throw new IllegalStateException("'in' handled above");
        };
    }

    private List<Object> inValues(Expr right, Map<String, QueryFieldBinding> bindings) {
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
            values.add(exprTranslator.toField(value, bindings));
        }
        return values;
    }

    /**
     * Compiles a {@code subquery}-valued {@code in} operand into a nested single-column select. Requires
     * an enclosing {@link TranslationContext} (from the {@code TranslationContext} overload); the subquery
     * must target the enclosing entity (same-entity only) so it reuses the enclosing table and field
     * bindings. Its <b>first</b> select column is the membership key: the built select is wrapped in a
     * derived table and only that first column is projected into the {@code IN}, so the subquery may
     * additionally select aggregates purely to drive its own {@code ORDER BY}/{@code LIMIT} (e.g.
     * {@code max(computed_at_ms)} to take the latest N groups).
     */
    private Select<? extends Record1<?>> subquerySelect(
            SubqueryExpr subquery, Map<String, QueryFieldBinding> bindings) {
        final TranslationContext ctx = subqueryContext.get();
        if (ctx == null) {
            throw new ValidationException("'in' subquery is not supported in this context");
        }
        final StructuredQuery inner = subquery.query();
        if (inner == null) {
            throw new ValidationException("'in' subquery requires a 'query'");
        }
        if (!ctx.entity().equals(inner.entity())) {
            throw new ValidationException(
                    "'in' subquery must target the same entity ('" + ctx.entity() + "'), not '" + inner.entity() + "'");
        }
        final SelectQuery<Record> subselect =
                queryBuilderProvider.getObject().build(ctx.dsl(), ctx.table(), bindings, inner);
        final Table<?> derived = subselect.asTable(DSL.name("in_subquery"));
        final Field<?> membershipKey = derived.field(0);
        if (membershipKey == null) {
            throw new ValidationException("'in' subquery must select at least one column (the membership key)");
        }
        return ctx.dsl().select(membershipKey).from(derived);
    }

    /** True only for a bare {@link FieldExpr} bound to an {@code ARRAY}-typed field. */
    private static boolean isArrayField(Expr expr, Map<String, QueryFieldBinding> bindings) {
        if (!(expr instanceof FieldExpr field)) {
            return false;
        }
        final QueryFieldBinding binding = bindings.get(field.name());
        return binding != null && binding.type() == QueryFieldType.ARRAY;
    }

    /**
     * Builds a JSONB array-element containment condition over {@code column}. A string element uses
     * the {@code ?} element-existence operator ({@code ??} escapes the jOOQ bind placeholder); any
     * other scalar literal uses {@code @>} against the element promoted to JSONB via {@code to_jsonb}.
     * The operand is always a bound parameter — never concatenated into SQL.
     */
    private Condition arrayContains(Field<JSONB> column, Expr right, Map<String, QueryFieldBinding> bindings) {
        if (right instanceof ValueExpr value && value.valueType() == ValueType.STRING && value.value() != null) {
            return DSL.condition("{0} ?? {1}", column, DSL.val(value.value()));
        }
        if (!(right instanceof ValueExpr)) {
            throw new ValidationException("'co'/'nc' on an array field require a scalar literal right operand");
        }
        return DSL.condition("{0} @> to_jsonb({1})", column, exprTranslator.toField(right, bindings));
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
