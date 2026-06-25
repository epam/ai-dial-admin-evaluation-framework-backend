package com.epam.aidial.evaluation.experimental.query.service.translate.function;

import com.epam.aidial.evaluation.experimental.query.model.Expr;
import com.epam.aidial.evaluation.experimental.query.model.FnExpr;
import com.epam.aidial.evaluation.experimental.query.service.QueryFieldBinding;
import com.epam.aidial.evaluation.experimental.query.service.translate.ExprTranslator;
import com.epam.aidial.evaluation.service.domain.exception.ValidationException;
import java.util.List;
import java.util.Map;
import org.jooq.Field;

/**
 * Per-invocation context handed to a {@link QueryFunction}. Carries the active field bindings and
 * parameter map, and exposes recursive translation back into {@link ExprTranslator} so a function
 * can translate its arguments (which may themselves be functions, fields, values, or params).
 */
public final class FunctionContext {

    private final ExprTranslator translator;
    private final Map<String, QueryFieldBinding> bindings;
    private final Map<String, Expr> params;

    public FunctionContext(
            ExprTranslator translator, Map<String, QueryFieldBinding> bindings, Map<String, Expr> params) {
        this.translator = translator;
        this.bindings = bindings;
        this.params = params;
    }

    /** Translates an argument expression to a jOOQ field (recurses through the translator). */
    public Field<?> toField(Expr expr) {
        return translator.toField(expr, bindings, params);
    }

    /** One-level parameter substitution (e.g. to inspect an array bound to a {@code param}). */
    public Expr substitute(Expr expr) {
        return translator.substituteParam(expr, params);
    }

    public List<Expr> args(FnExpr fn) {
        return fn.args() == null ? List.of() : fn.args();
    }

    /** Translates the single argument of {@code fn}, rejecting any other arity. */
    public Field<?> singleArg(FnExpr fn) {
        final List<Expr> args = args(fn);
        if (args.size() != 1) {
            throw new ValidationException("function '" + fn.name() + "' expects exactly one argument");
        }
        return toField(args.getFirst());
    }

    public Map<String, QueryFieldBinding> bindings() {
        return bindings;
    }

    public Map<String, Expr> params() {
        return params;
    }
}
