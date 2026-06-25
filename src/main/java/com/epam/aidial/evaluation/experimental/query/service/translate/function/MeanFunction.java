package com.epam.aidial.evaluation.experimental.query.service.translate.function;

import com.epam.aidial.evaluation.configuration.logging.LogExecution;
import com.epam.aidial.evaluation.experimental.query.model.ArrayExpr;
import com.epam.aidial.evaluation.experimental.query.model.Expr;
import com.epam.aidial.evaluation.experimental.query.model.FnExpr;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.math.BigDecimal;
import java.util.List;
import org.jooq.Field;
import org.jooq.impl.DSL;
import org.springframework.stereotype.Component;

/**
 * {@code mean(array)} — the unweighted mean of the array's elements: {@code (e₁ + … + eₙ) / n}. The
 * argument is an {@link ArrayExpr} (typically supplied via a {@code param} bound to the run's
 * per-metric {@code avg(...)} terms), so a single definition can average a dynamic set of metrics.
 *
 * <p>First example of a pluggable {@link QueryFunction}; weighted/arithmetic variants would be added
 * the same way (new beans) without touching the translator.
 *
 * <p>Null note: if a metric's {@code avg} is null its term nulls the sum (rare — a metric present in
 * the snapshot but with no values). The common case (metrics have values) matches the prior
 * engine-side "mean of per-metric averages".
 */
@Component
@LogExecution
public class MeanFunction implements QueryFunction {

    @Override
    public String name() {
        return "mean";
    }

    @Override
    public Field<?> translate(FnExpr fn, FunctionContext ctx) {
        final List<Expr> args = ctx.args(fn);
        if (args.size() != 1) {
            throw new ValidationException("function 'mean' expects exactly one array argument");
        }
        final Expr resolved = ctx.substitute(args.getFirst());
        if (!(resolved instanceof ArrayExpr array)) {
            throw new ValidationException("function 'mean' expects an array argument");
        }
        final List<Expr> items = array.items() == null ? List.of() : array.items();
        if (items.isEmpty()) {
            throw new ValidationException("function 'mean' requires a non-empty array");
        }
        Field<BigDecimal> sum = null;
        for (final Expr item : items) {
            final Field<BigDecimal> term = ctx.toField(item).cast(BigDecimal.class);
            sum = sum == null ? term : sum.add(term);
        }
        return sum.divide(DSL.val(BigDecimal.valueOf(items.size())));
    }
}
