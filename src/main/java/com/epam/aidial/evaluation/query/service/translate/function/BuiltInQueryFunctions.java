package com.epam.aidial.evaluation.query.service.translate.function;

import com.epam.aidial.evaluation.data.db.repository.sql.DialectAwareSql;
import com.epam.aidial.evaluation.query.model.Expr;
import com.epam.aidial.evaluation.query.model.FnExpr;
import com.epam.aidial.evaluation.query.model.ValueExpr;
import com.epam.aidial.evaluation.query.service.translate.ValueExprToObjectMapper;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.math.BigDecimal;
import java.util.List;
import java.util.function.BinaryOperator;
import lombok.RequiredArgsConstructor;
import org.jooq.Field;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.jooq.impl.SQLDataType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The closed catalog of built-in query-DSL functions, each registered as a {@link QueryFunction}
 * bean (collected by {@link QueryFunctionRegistry}). Behaviour mirrors the former hardcoded switch in
 * {@code ExprTranslator}; the catalog is now extension-driven (add a bean to add a function).
 */
@Configuration
@RequiredArgsConstructor
public class BuiltInQueryFunctions {

    private final ValueExprToObjectMapper valueExprToObjectMapper;

    @Bean
    @SuppressWarnings({"unchecked"})
    public QueryFunction lowerFunction() {
        return QueryFunction.of(QueryFunctionNames.LOWER, (fn, ctx) -> DSL.lower((Field<String>) ctx.singleArg(fn)));
    }

    @Bean
    @SuppressWarnings({"unchecked"})
    public QueryFunction upperFunction() {
        return QueryFunction.of(QueryFunctionNames.UPPER, (fn, ctx) -> DSL.upper((Field<String>) ctx.singleArg(fn)));
    }

    @Bean
    @SuppressWarnings({"unchecked"})
    public QueryFunction lengthFunction() {
        return QueryFunction.of("length", (fn, ctx) -> DSL.length((Field<String>) ctx.singleArg(fn)));
    }

    @Bean
    @SuppressWarnings({"unchecked"})
    public QueryFunction trimFunction() {
        return QueryFunction.of("trim", (fn, ctx) -> DSL.trim((Field<String>) ctx.singleArg(fn)));
    }

    @Bean
    @SuppressWarnings({"unchecked", "rawtypes"})
    public QueryFunction absFunction() {
        return QueryFunction.of("abs", (fn, ctx) -> DSL.abs((Field) ctx.singleArg(fn)));
    }

    @Bean
    @SuppressWarnings({"unchecked", "rawtypes"})
    public QueryFunction widthBucketFunction() {
        return QueryFunction.of("width_bucket", (fn, ctx) -> {
            final List<Expr> args = ctx.args(fn);
            if (args.size() != 4) {
                throw new ValidationException(
                        "function 'width_bucket' expects exactly four arguments (operand, low, high, count)");
            }
            final Field operand = ctx.toField(args.get(0));
            final Field low = ctx.toField(args.get(1));
            final Field high = ctx.toField(args.get(2));
            final Field<Integer> count = ctx.toField(args.get(3)).cast(Integer.class);
            return DSL.widthBucket(operand, low, high, count);
        });
    }

    @Bean
    @SuppressWarnings({"unchecked", "rawtypes"})
    public QueryFunction countFunction() {
        return QueryFunction.of("count", (fn, ctx) -> {
            final List<Expr> args = ctx.args(fn);
            if (args.isEmpty()) {
                return DSL.count();
            }
            final Field arg = ctx.singleArg(fn);
            return fn.distinct() ? DSL.countDistinct(arg) : DSL.count(arg);
        });
    }

    @Bean
    @SuppressWarnings({"unchecked", "rawtypes"})
    public QueryFunction sumFunction() {
        return QueryFunction.of("sum", (fn, ctx) -> {
            final Field arg = ctx.singleArg(fn);
            return fn.distinct() ? DSL.sumDistinct(arg) : DSL.sum(arg);
        });
    }

    @Bean
    @SuppressWarnings({"unchecked", "rawtypes"})
    public QueryFunction avgFunction() {
        return QueryFunction.of("avg", (fn, ctx) -> {
            final Field arg = ctx.singleArg(fn);
            return fn.distinct() ? DSL.avgDistinct(arg) : DSL.avg(arg);
        });
    }

    @Bean
    @SuppressWarnings({"unchecked", "rawtypes"})
    public QueryFunction minFunction() {
        return QueryFunction.of("min", (fn, ctx) -> DSL.min((Field) ctx.singleArg(fn)));
    }

    @Bean
    @SuppressWarnings({"unchecked", "rawtypes"})
    public QueryFunction maxFunction() {
        return QueryFunction.of("max", (fn, ctx) -> DSL.max((Field) ctx.singleArg(fn)));
    }

    @Bean
    public QueryFunction percentileContFunction() {
        return QueryFunction.of("percentile_cont", (fn, ctx) -> percentile(fn, ctx, true));
    }

    @Bean
    public QueryFunction percentileDiscFunction() {
        return QueryFunction.of("percentile_disc", (fn, ctx) -> percentile(fn, ctx, false));
    }

    @Bean
    public QueryFunction addFunction() {
        return QueryFunction.of("add", (fn, ctx) -> reduce(fn, ctx, "add", Field::add));
    }

    @Bean
    public QueryFunction multiplyFunction() {
        return QueryFunction.of("multiply", (fn, ctx) -> reduce(fn, ctx, "multiply", Field::mul));
    }

    @Bean
    public QueryFunction subtractFunction() {
        return QueryFunction.of("subtract", (fn, ctx) -> binary(fn, ctx, "subtract", Field::sub));
    }

    @Bean
    public QueryFunction divideFunction() {
        return QueryFunction.of("divide", (fn, ctx) -> binary(fn, ctx, "divide", Field::div));
    }

    @Bean
    public QueryFunction coalesceFunction() {
        return QueryFunction.of("coalesce", (fn, ctx) -> {
            final List<Expr> args = ctx.args(fn);
            if (args.size() != 2) {
                throw new ValidationException("function 'coalesce' expects exactly two arguments");
            }
            final Field<BigDecimal> value = ctx.toField(args.get(0)).cast(BigDecimal.class);
            final Field<BigDecimal> fallback = ctx.toField(args.get(1)).cast(BigDecimal.class);
            return DSL.coalesce(value, fallback);
        });
    }

    /** {@code add}/{@code multiply}: n-ary (≥1 arg), left-folded via {@code combiner}. */
    private Field<BigDecimal> reduce(
            FnExpr fn, FunctionContext ctx, String name, BinaryOperator<Field<BigDecimal>> combiner) {
        final List<Expr> args = ctx.args(fn);
        if (args.isEmpty()) {
            throw new ValidationException("function '" + name + "' expects at least one argument");
        }
        Field<BigDecimal> result = null;
        for (final Expr arg : args) {
            final Field<BigDecimal> term = ctx.toField(arg).cast(BigDecimal.class);
            result = result == null ? term : combiner.apply(result, term);
        }
        return result;
    }

    /** {@code subtract}/{@code divide}: binary only (exactly 2 args). */
    private Field<BigDecimal> binary(
            FnExpr fn, FunctionContext ctx, String name, BinaryOperator<Field<BigDecimal>> combiner) {
        final List<Expr> args = ctx.args(fn);
        if (args.size() != 2) {
            throw new ValidationException("function '" + name + "' expects exactly two arguments");
        }
        final Field<BigDecimal> left = ctx.toField(args.get(0)).cast(BigDecimal.class);
        final Field<BigDecimal> right = ctx.toField(args.get(1)).cast(BigDecimal.class);
        return combiner.apply(left, right);
    }

    /**
     * {@code percentile_(cont|disc)(fraction, column)} → ordered-set aggregate with {@code WITHIN
     * GROUP (ORDER BY column)}. {@code fraction} must be a numeric literal in {@code [0, 1]}.
     *
     * <p>On {@link SQLDialect#CLICKHOUSE}, jOOQ's {@code DSL.percentileCont}/{@code percentileDisc}
     * render as ClickHouse's {@code quantile}/{@code quantileExactLow} parametric aggregate functions.
     * {@code quantile} is an <em>approximate</em>, sampling-based estimate — not the exact linear
     * interpolation Postgres' {@code percentile_cont} computes — so metric statistics (e.g. P90/P99)
     * would become nondeterministic and diverge semantically from Postgres. This dialect-switches to
     * the exact ClickHouse equivalents instead: {@code quantileExactInclusive(fraction)(column)} for
     * {@code percentile_cont}, {@code quantileExactLow(fraction)(column)} for {@code percentile_disc}
     * (both exact, matching jOOQ's already-exact rendering for {@code percentile_disc} — only {@code
     * percentile_cont} needed correcting). The fraction is inlined rather than bound: it is already
     * validated as a numeric literal in {@code [0, 1]} by {@link #percentileFraction}, never
     * user-controlled SQL text.
     */
    private Field<?> percentile(FnExpr fn, FunctionContext ctx, boolean continuous) {
        final List<Expr> args = ctx.args(fn);
        if (args.size() != 2) {
            throw new ValidationException(
                    "function '" + fn.name() + "' expects exactly two arguments (fraction, column)");
        }
        final BigDecimal fractionValue = percentileFraction(fn, args.getFirst());
        final Field<BigDecimal> fraction = DSL.val(fractionValue);
        final Field<?> orderField = ctx.toField(args.get(1));
        return DialectAwareSql.field(
                continuous ? "percentile_cont" : "percentile_disc",
                SQLDataType.NUMERIC,
                family -> family == SQLDialect.CLICKHOUSE
                        ? DSL.field(
                                (continuous ? "quantileExactInclusive({0})({1})" : "quantileExactLow({0})({1})"),
                                SQLDataType.NUMERIC,
                                DSL.inline(fractionValue),
                                orderField)
                        : (continuous
                                ? DSL.percentileCont(fraction).withinGroupOrderBy(orderField)
                                : DSL.percentileDisc(fraction).withinGroupOrderBy(orderField)));
    }

    private BigDecimal percentileFraction(FnExpr fn, Expr arg) {
        if (!(arg instanceof ValueExpr value)) {
            throw new ValidationException(
                    "function '" + fn.name() + "' requires a numeric literal fraction as its first argument");
        }
        if (!(valueExprToObjectMapper.map(value) instanceof Number number)) {
            throw new ValidationException("function '" + fn.name() + "' fraction must be a numeric literal");
        }
        final BigDecimal fraction = new BigDecimal(number.toString());
        if (fraction.compareTo(BigDecimal.ZERO) < 0 || fraction.compareTo(BigDecimal.ONE) > 0) {
            throw new ValidationException("function '" + fn.name() + "' fraction must be within [0, 1]");
        }
        return fraction;
    }
}
