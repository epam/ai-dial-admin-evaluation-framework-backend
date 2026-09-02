package com.epam.aidial.evaluation.query.service.translate;

import com.epam.aidial.evaluation.data.db.repository.sql.ClickHouseTypeNames;
import com.epam.aidial.evaluation.data.db.repository.sql.DialectAwareSql;
import com.epam.aidial.evaluation.query.model.ArrayExpr;
import com.epam.aidial.evaluation.query.model.ComparisonNode;
import com.epam.aidial.evaluation.query.model.ComparisonOp;
import com.epam.aidial.evaluation.query.model.Expr;
import com.epam.aidial.evaluation.query.model.FieldExpr;
import com.epam.aidial.evaluation.query.model.FilterNode;
import com.epam.aidial.evaluation.query.model.FnExpr;
import com.epam.aidial.evaluation.query.model.LogicalNode;
import com.epam.aidial.evaluation.query.model.SubqueryExpr;
import com.epam.aidial.evaluation.query.model.ValueExpr;
import com.epam.aidial.evaluation.query.model.ValueType;
import com.epam.aidial.evaluation.query.service.QueryFieldBinding;
import com.epam.aidial.evaluation.query.service.dto.QueryFieldType;
import com.epam.aidial.evaluation.query.service.translate.function.QueryFunctionNames;
import com.epam.aidial.evaluation.runner.config.logging.LogExecution;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jooq.Condition;
import org.jooq.Field;
import org.jooq.JSONB;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
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
 * <p>A {@code subquery}-valued {@code in} compiles to a nested {@code SELECT} ({@code left IN (SELECT
 * …)}) via {@link ExprTranslator#compileSubqueryMembership}, which reaches back into
 * {@code StructuredQueryBuilder} lazily — this class has no dependency on the builder at all.
 */
@Component
@LogExecution
@RequiredArgsConstructor
public class FilterTranslator {

    /** Escape character for {@code co}/{@code nc} LIKE patterns so operand metacharacters match literally. */
    private static final char LIKE_ESCAPE = '\\';

    private final ExprTranslator exprTranslator;

    /** Translates a filter tree into a jOOQ {@link Condition} ({@code null} tree → {@code TRUE}). */
    public Condition toCondition(FilterNode node, Map<String, QueryFieldBinding> bindings) {
        if (node == null) {
            return DSL.trueCondition();
        }
        return switch (node) {
            case LogicalNode logical -> toLogical(logical, bindings);
            case ComparisonNode comparison -> toComparison(comparison, bindings);
        };
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
                yield negate(toCondition(args.get(0), bindings));
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

        // Array-element containment: `co`/`nc` on an array-typed field means "the JSONB array contains
        // this element", not substring LIKE. The operand is either the bare ARRAY-bound field or that
        // field under a case-normalizing wrapper; anything else keeps LIKE.
        if (op == ComparisonOp.CO || op == ComparisonOp.NC) {
            final ArrayOperand arrayOperand = resolveArrayOperand(leftExpr, bindings);
            if (arrayOperand != null && !isNullLiteral(right)) {
                final Field<JSONB> column = (Field<JSONB>) exprTranslator.toField(arrayOperand.field(), bindings);
                return arrayContains(column, right, bindings, arrayOperand.ignoreCase(), op.negated());
            }
        }

        final Field left = exprTranslator.toField(leftExpr, bindings);

        if (op == ComparisonOp.IN) {
            if (right instanceof SubqueryExpr subquery) {
                return left.in(exprTranslator.compileSubqueryMembership(subquery));
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
        final Condition condition =
                switch (op) {
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
        return op.negated() ? nullSatisfies(condition) : condition;
    }

    /**
     * Makes a negated comparison total: an UNKNOWN result — which SQL three-valued logic produces whenever
     * an operand is null — counts as satisfied, because a missing value cannot contain or equal anything.
     * Without this, {@code nc}/{@code ne} silently drop null-valued rows, and inside the multi-turn
     * ALL-turns-match quantifier (whose {@code IS NOT TRUE} treats UNKNOWN as a failing turn) a single turn
     * with a null field excludes the whole test case. Deliberately not applied to positive operators: their
     * UNKNOWN already means "no match" everywhere, and wrapping them would put a {@code BooleanTest} around
     * otherwise sargable predicates on indexed columns.
     */
    private static Condition nullSatisfies(Condition condition) {
        return DialectAwareSql.condition(family -> nullSatisfiesRaw(family, condition));
    }

    /**
     * The family-specific SQL {@link #nullSatisfies} renders, without the {@link DialectAwareSql}
     * wrapper — factored out so callers that already sit inside a {@code byFamily} closure (e.g. the
     * negated array-containment forms below) can fold it in without nesting a second
     * {@code CustomCondition}: jOOQ's {@code NOT} combinator adds a defensive extra parenthesis around
     * a {@code CustomCondition} operand that a plain, jOOQ-native condition does not get, so every
     * negated form here is built as ONE flat family-specific expression rather than composing multiple
     * dialect-aware conditions through {@code DSL.not(...)}.
     */
    private static Condition nullSatisfiesRaw(SQLDialect family, Condition condition) {
        return family == SQLDialect.CLICKHOUSE
                ? DSL.condition("ifNull(({0}), true)", condition)
                : DSL.condition("({0}) is not false", condition);
    }

    /**
     * Negates a child predicate so that an UNKNOWN child is negated to {@code true}, keeping {@code not}
     * consistent with the negated comparison operators (see {@link #nullSatisfies}). Plain {@code NOT} would
     * propagate UNKNOWN and drop the row instead.
     */
    private static Condition negate(Condition condition) {
        return DialectAwareSql.condition(family -> family == SQLDialect.CLICKHOUSE
                ? DSL.condition("not(ifNull(({0}), false))", condition)
                : DSL.condition("({0}) is not true", condition));
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
     * The left operand of a {@code co}/{@code nc} comparison resolved to an array field: the bare
     * {@code ARRAY}-bound {@link FieldExpr} to translate, plus whether the comparison must fold case.
     */
    private record ArrayOperand(FieldExpr field, boolean ignoreCase) {}

    /**
     * Resolves a {@code co}/{@code nc} left operand to an {@link ArrayOperand}, or {@code null} when it
     * is not an array field at all (a scalar field, a function over a non-array field, any other
     * expression — all of which keep scalar LIKE).
     *
     * <p>A bare {@link FieldExpr} bound to {@code ARRAY} compares case-sensitively. A single-argument
     * {@code lower}/{@code upper} around such a field is read as "compare ignoring case" and the
     * wrapper is discarded: {@code lower(jsonb)}/{@code upper(jsonb)} do not exist in Postgres, so
     * translating that shape literally can only produce a statement that fails at execution
     * (SQLSTATE 42883, GH #142) — case normalization is the sole intent it can express. The wrapper name
     * is matched **ignoring case**, exactly as {@code QueryFunctionRegistry} resolves it, so {@code LOWER}
     * cannot slip past this routing into the failing literal translation.
     */
    private static ArrayOperand resolveArrayOperand(Expr expr, Map<String, QueryFieldBinding> bindings) {
        if (isArrayField(expr, bindings)) {
            return new ArrayOperand((FieldExpr) expr, false);
        }
        if (expr instanceof FnExpr fn
                && QueryFunctionNames.isCaseNormalizing(fn.name())
                && fn.args() != null
                && fn.args().size() == 1
                && isArrayField(fn.args().get(0), bindings)) {
            return new ArrayOperand((FieldExpr) fn.args().get(0), true);
        }
        return null;
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
     * Builds a JSONB array-element containment condition over {@code column}, negated (and made total
     * via {@link #nullSatisfiesRaw}) when {@code negated} holds. A string element uses the {@code ?}
     * element-existence operator ({@code ??} escapes the jOOQ bind placeholder), or — when {@code
     * ignoreCase} holds — case-folded whole-element comparison over the expanded array; any other
     * scalar literal uses {@code @>} against the element promoted to JSONB via {@code to_jsonb} (case
     * folding is meaningless for a non-string literal, so the wrapper is simply dropped). The operand
     * is always a bound parameter — never concatenated into SQL.
     *
     * <p>The {@code negated} branch is applied via {@code DSL.not(...)} to the plain, family-specific
     * condition <em>inside</em> the same {@link DialectAwareSql} closure that picks the family, rather
     * than by wrapping an already-built dialect-aware {@link Condition} from the outside: jOOQ's
     * {@code NOT} combinator adds a defensive extra parenthesis around a {@code CustomCondition}
     * operand that a plain, jOOQ-native condition does not get, so composing {@code NOT} externally
     * would silently add a parenthesis layer to the rendered SQL.
     */
    private Condition arrayContains(
            Field<JSONB> column,
            Expr right,
            Map<String, QueryFieldBinding> bindings,
            boolean ignoreCase,
            boolean negated) {
        if (right instanceof ValueExpr value && value.valueType() == ValueType.STRING && value.value() != null) {
            return ignoreCase
                    ? arrayContainsIgnoreCase(column, value.value(), negated)
                    : arrayContainsStringElement(column, value.value(), negated);
        }
        if (!(right instanceof ValueExpr value)) {
            throw new ValidationException("'co'/'nc' on an array field require a scalar literal right operand");
        }
        return arrayContainsScalarElement(column, value.valueType(), exprTranslator.toField(right, bindings), negated);
    }

    /**
     * Element-existence containment for a string operand: Postgres' {@code ?} JSONB key/element
     * existence operator ({@code ??} escapes the jOOQ bind placeholder). On ClickHouse — where the
     * JSONB-typed field is backed by a JSON-text {@code String} column — the array is decoded via
     * {@code JSONExtract(col, 'Array(Nullable(String))')} and tested with {@code has}.
     */
    private static Condition arrayContainsStringElement(Field<JSONB> column, String value, boolean negated) {
        return DialectAwareSql.condition(family -> {
            final Condition raw = family == SQLDialect.CLICKHOUSE
                    ? DSL.condition(
                            "has(JSONExtract({0}, '" + ClickHouseTypeNames.ARRAY_NULLABLE_STRING + "'), {1})",
                            column,
                            DSL.val(value))
                    : DSL.condition("{0} ?? {1}", column, DSL.val(value));
            return negated ? nullSatisfiesRaw(family, DSL.not(raw)) : raw;
        });
    }

    /**
     * Element-existence containment for a non-string scalar operand: Postgres' {@code @>} JSONB
     * containment against the operand promoted via {@code to_jsonb} — unchanged for every literal
     * type. The ClickHouse branch dispatches on the literal's {@link ValueType}, known at translate
     * time: a {@code number} type ({@code integer}/{@code long}/{@code decimal}/{@code timestamp}, the
     * last stored as epoch milliseconds) compares every decoded array element as {@code
     * Nullable(Float64)}; {@code boolean} compares as {@code Nullable(Bool)}. Any other type ({@code
     * date}, {@code uuid}) has no faithful ClickHouse rendering — {@code JSONExtract} cannot recover a
     * date/UUID's original textual form from a JSON-text array element the way Postgres' {@code @>}
     * can via native JSONB equality — so the ClickHouse branch rejects it with a {@link
     * ValidationException} (surfaced as HTTP 400 by {@code DefaultExceptionHandler}) rather than
     * silently rendering a comparison that can never match. The type check runs inside the {@code
     * byFamily} closure, gated on {@code family == CLICKHOUSE}, so the same query still executes
     * unchanged on Postgres.
     */
    private static Condition arrayContainsScalarElement(
            Field<JSONB> column, ValueType valueType, Field<?> value, boolean negated) {
        return DialectAwareSql.condition(family -> {
            final Condition raw = family == SQLDialect.CLICKHOUSE
                    ? clickHouseScalarContains(valueType, column, value)
                    : DSL.condition("{0} @> to_jsonb({1})", column, value);
            return negated ? nullSatisfiesRaw(family, DSL.not(raw)) : raw;
        });
    }

    private static Condition clickHouseScalarContains(ValueType valueType, Field<JSONB> column, Field<?> value) {
        if (isNumericContainmentLiteral(valueType)) {
            return DSL.condition(
                    "arrayExists(x -> JSONExtract(x, '" + ClickHouseTypeNames.NULLABLE_FLOAT64 + "') = {1}, "
                            + "JSONExtractArrayRaw({0}))",
                    column,
                    value);
        }
        if (valueType == ValueType.BOOLEAN) {
            return DSL.condition(
                    "arrayExists(x -> JSONExtract(x, '" + ClickHouseTypeNames.NULLABLE_BOOL + "') = {1}, "
                            + "JSONExtractArrayRaw({0}))",
                    column,
                    value);
        }
        throw new ValidationException("array containment with " + valueType.code()
                + " literal is not supported on the ClickHouse analytics vendor");
    }

    /** {@code timestamp} is included: {@link ValueExprToObjectMapper} maps it to epoch-millisecond {@code Long}. */
    private static boolean isNumericContainmentLiteral(ValueType valueType) {
        return valueType == ValueType.INTEGER
                || valueType == ValueType.LONG
                || valueType == ValueType.DECIMAL
                || valueType == ValueType.TIMESTAMP;
    }

    /**
     * Case-insensitive whole-element containment: expands the array with {@code jsonb_array_elements_text}
     * and compares every element to the bound operand case-folded. Two documented divergences from the
     * bare-field {@code ?} form follow from that: elements are compared by their JSON <em>text
     * rendering</em>, so a string operand also matches an equally-rendered non-string element ({@code "1"}
     * matches the element {@code 1}), while a non-array value never matches — {@code ?} instead treats a
     * string value as an equality test and an object value as a key test, so it <em>does</em> match those.
     *
     * <p>The {@code array}-type guard sits <em>inside</em> the function argument rather than as a sibling
     * {@code AND} conjunct: {@code jsonb_array_elements_text} raises on a scalar or object value, and the
     * planner is free to cost-order top-level conjuncts, so only {@code CASE} — which evaluates just the
     * selected branch — guarantees the guard holds under any plan. That guard is also what makes
     * {@code nc} total here: a null, absent, or non-array value yields the empty array, so {@code EXISTS}
     * is {@code false} (never UNKNOWN) and its negation is {@code true} on its own. {@link #nullSatisfiesRaw}
     * still wraps the negation for uniformity with the {@code ?}/{@code @>} forms, where the operand
     * genuinely can be null — here it is inert.
     *
     * <p>The ClickHouse branch decodes the array via {@code JSONExtract(col, 'Array(Nullable(String))')}
     * and folds case with {@code lowerUTF8} inside {@code arrayExists}, the ClickHouse equivalent of the
     * Postgres {@code EXISTS}-over-expanded-array shape above.
     */
    private static Condition arrayContainsIgnoreCase(Field<JSONB> column, String operand, boolean negated) {
        return DialectAwareSql.condition(family -> {
            final Condition raw = family == SQLDialect.CLICKHOUSE
                    ? DSL.condition(
                            "arrayExists(x -> lowerUTF8(x) = lowerUTF8({1}), " + "JSONExtract({0}, '"
                                    + ClickHouseTypeNames.ARRAY_NULLABLE_STRING + "'))",
                            column,
                            DSL.val(operand))
                    : DSL.condition(
                            "exists (select 1 from jsonb_array_elements_text("
                                    + "case when jsonb_typeof({0}) = 'array' then {0} else '[]'::jsonb end) as e(v) "
                                    + "where lower(e.v) = lower({1}))",
                            column, DSL.val(operand));
            return negated ? nullSatisfiesRaw(family, DSL.not(raw)) : raw;
        });
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
